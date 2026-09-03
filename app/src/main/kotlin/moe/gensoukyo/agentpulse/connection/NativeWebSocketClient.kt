package moe.gensoukyo.agentpulse.connection

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.gensoukyo.agentpulse.BuildConfig
import moe.gensoukyo.agentpulse.data.HostProfile
import moe.gensoukyo.agentpulse.data.ConnectionRoute
import moe.gensoukyo.agentpulse.protocol.NATIVE_PATH
import moe.gensoukyo.agentpulse.protocol.NATIVE_SUBPROTOCOL
import moe.gensoukyo.agentpulse.protocol.NativeClientMessage
import moe.gensoukyo.agentpulse.protocol.NativeCodec
import moe.gensoukyo.agentpulse.protocol.NativeSessionReducer
import moe.gensoukyo.agentpulse.protocol.FormAnswer
import moe.gensoukyo.agentpulse.protocol.DomainEnvelope
import moe.gensoukyo.agentpulse.protocol.NativeState
import moe.gensoukyo.agentpulse.protocol.UuidV7
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class NativeWebSocketClient(
    private val profile: HostProfile,
    private val clientId: String,
    initialState: NativeState,
    private val onState: (NativeState) -> Unit,
    private val onEventState: (NativeState, NativeState) -> Unit,
    private val onCommandSubmission: (CommandSubmission) -> Unit,
) {
    private val completion = CompletableDeferred<Throwable?>()
    private val reducer = NativeSessionReducer(initialState = initialState)
    private val client = caClient(
        profile.serverName,
        profile.lastAddress,
        profile.caCertificateDer,
        if (profile.selectedRoute == ConnectionRoute.RELAY) {
            val endpoint = RelayEndpoint.parse(
                requireNotNull(profile.relayEndpoint) { "Relay route has no configured endpoint" },
            )
            RelayTunnelSocketFactory(endpoint, profile.accessToken)
        } else {
            null
        },
    )
    private val finished = AtomicBoolean(false)
    private val refreshExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "agentpulse-session-refresh").apply { isDaemon = true }
    }
    private var socket: WebSocket? = null
    private val commandTracker = CommandSubmissionTracker()

    fun connect() {
        val request = Request.Builder()
            .url("https://${profile.serverName}:${profile.lastPort}$NATIVE_PATH")
            .header("Sec-WebSocket-Protocol", NATIVE_SUBPROTOCOL)
            .header("Authorization", "Bearer ${profile.accessToken}")
            .header("X-AgentPulse-Client-Id", clientId)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (finished.get()) {
                    webSocket.cancel()
                    return
                }
                if (response.header("Sec-WebSocket-Protocol") != NATIVE_SUBPROTOCOL) {
                    webSocket.close(1002, "subprotocol mismatch")
                    finish(IllegalStateException("Native subprotocol was not accepted"))
                    return
                }
                if (!webSocket.send(
                    NativeCodec.encode(
                        NativeClientMessage.Hello(
                            clientId = clientId,
                            displayName = android.os.Build.MODEL.ifBlank { "Android" },
                            version = BuildConfig.VERSION_NAME,
                            hostRunId = reducer.state.hostRunId,
                            sessionCursors = reducer.state.sessions.mapValues { it.value.cursor }
                                .filterValues { it > 0UL },
                        ),
                    ),
                )) {
                    webSocket.cancel()
                    finish(IllegalStateException("Native client hello could not be queued"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (finished.get()) return
                if (text.encodeToByteArray().size > MAX_FRAME_BYTES) {
                    webSocket.close(1009, "frame too large")
                    finish(IllegalStateException("Native frame exceeded the 1 MiB limit"))
                    return
                }
                synchronized(this@NativeWebSocketClient) {
                    runCatching {
                        val decoded = NativeCodec.decode(text)
                        if (handleCommandControl(decoded)) return@runCatching
                        val before = reducer.state
                        val outgoing = reducer.accept(decoded)
                        val after = reducer.state
                        onState(after)
                        onEventState(before, after)
                        outgoing.forEach {
                            if (!webSocket.send(NativeCodec.encode(it))) {
                                throw IllegalStateException("Native client message could not be queued")
                            }
                        }
                    }.onFailure {
                        webSocket.close(1002, "protocol failure")
                        finish(it)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish(t)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(null)
        })
        refreshExecutor.scheduleWithFixedDelay(
            ::refreshSessions,
            SESSION_REFRESH_SECONDS,
            SESSION_REFRESH_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    suspend fun awaitClose(): Throwable? = completion.await()

    @Synchronized
    fun submitApproval(sessionId: String, interactionId: String, optionId: String): Result<Unit> =
        runCatching {
            val webSocket = socket ?: throw IllegalStateException("Native connection is unavailable")
            val message = reducer.submitApproval(sessionId, interactionId, optionId)
            onState(reducer.state)
            try {
                if (!webSocket.send(NativeCodec.encode(message))) {
                    throw IllegalStateException("Approval response could not be queued")
                }
            } catch (error: Exception) {
                reducer.failApprovalSubmission(
                    message.requestId,
                    error.message ?: "Approval response could not be queued",
                )
                onState(reducer.state)
                throw error
            }
        }

    @Synchronized
    fun submitForm(
        sessionId: String,
        interactionId: String,
        answers: Map<String, FormAnswer>,
    ): Result<Unit> = runCatching {
        val webSocket = socket ?: throw IllegalStateException("Native connection is unavailable")
        val message = reducer.submitForm(sessionId, interactionId, answers)
        onState(reducer.state)
        try {
            if (!webSocket.send(NativeCodec.encode(message))) {
                throw IllegalStateException("Form response could not be queued")
            }
        } catch (error: Exception) {
            reducer.failApprovalSubmission(message.requestId, error.message ?: "Form response could not be queued")
            onState(reducer.state)
            throw error
        }
    }

    @Synchronized
    fun submitCommand(
        requestId: String,
        commandId: String,
        sessionId: String,
        command: DomainEnvelope,
    ): Result<Unit> = runCatching {
        val webSocket = socket ?: throw IllegalStateException("Native connection is unavailable")
        onCommandSubmission(commandTracker.begin(requestId, commandId, sessionId))
        try {
            val message = NativeClientMessage.SubmitCommand(requestId, command)
            if (!webSocket.send(NativeCodec.encode(message))) {
                throw IllegalStateException("Command could not be queued")
            }
        } catch (error: Exception) {
            commandTracker.fail(requestId, error.message ?: "Command could not be queued")
                ?.let(onCommandSubmission)
            throw error
        }
    }

    fun close() {
        socket?.close(1000, "user disconnected")
        socket = null
        finish(null)
    }

    private fun refreshSessions() {
        if (finished.get()) return
        synchronized(this@NativeWebSocketClient) {
            val webSocket = socket ?: return
            val message = reducer.refreshSessions() ?: return
            if (!webSocket.send(NativeCodec.encode(message))) {
                webSocket.cancel()
                finish(IllegalStateException("Session refresh could not be queued"))
            }
        }
    }

    @Synchronized
    private fun handleCommandControl(message: moe.gensoukyo.agentpulse.protocol.NativeServerMessage): Boolean {
        val requestId = when (message) {
            is moe.gensoukyo.agentpulse.protocol.NativeServerMessage.CommandResult -> message.requestId
            is moe.gensoukyo.agentpulse.protocol.NativeServerMessage.Error -> message.requestId
            else -> null
        } ?: return false
        val submission = commandTracker.complete(message) ?: return false
        onCommandSubmission(submission)
        return true
    }

    @Synchronized
    private fun failPendingCommands(message: String) {
        commandTracker.failAll(message).forEach(onCommandSubmission)
    }

    private fun finish(error: Throwable?) {
        if (!finished.compareAndSet(false, true)) return
        failPendingCommands(error?.message ?: "Connection closed before Host confirmation")
        completion.complete(error)
        refreshExecutor.shutdownNow()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val MAX_FRAME_BYTES = 1024 * 1024
        private const val SESSION_REFRESH_SECONDS = 2L
    }
}

private data class PendingCommand(val commandId: String, val sessionId: String)

internal class CommandSubmissionTracker {
    private val pending = mutableMapOf<String, PendingCommand>()

    fun begin(requestId: String, commandId: String, sessionId: String): CommandSubmission {
        check(pending.putIfAbsent(requestId, PendingCommand(commandId, sessionId)) == null) {
            "Command request is already pending"
        }
        return CommandSubmission(commandId, sessionId, CommandSubmissionPhase.SENDING)
    }

    fun complete(message: moe.gensoukyo.agentpulse.protocol.NativeServerMessage): CommandSubmission? {
        val requestId = when (message) {
            is moe.gensoukyo.agentpulse.protocol.NativeServerMessage.CommandResult -> message.requestId
            is moe.gensoukyo.agentpulse.protocol.NativeServerMessage.Error -> message.requestId
            else -> null
        } ?: return null
        val command = pending[requestId] ?: return null
        return when (message) {
            is moe.gensoukyo.agentpulse.protocol.NativeServerMessage.CommandResult -> {
                check(message.commandId == command.commandId && message.sessionId == command.sessionId) {
                    "Command result correlation mismatch"
                }
                pending.remove(requestId)
                CommandSubmission(command.commandId, command.sessionId, CommandSubmissionPhase.ACCEPTED)
            }
            is moe.gensoukyo.agentpulse.protocol.NativeServerMessage.Error if message.recoverable -> {
                pending.remove(requestId)
                CommandSubmission(
                    command.commandId,
                    command.sessionId,
                    CommandSubmissionPhase.FAILED,
                    message.message,
                )
            }
            else -> null
        }
    }

    fun fail(requestId: String, message: String): CommandSubmission? {
        val command = pending.remove(requestId) ?: return null
        return CommandSubmission(command.commandId, command.sessionId, CommandSubmissionPhase.FAILED, message)
    }

    fun failAll(message: String): List<CommandSubmission> {
        val submissions = pending.values.map {
            CommandSubmission(it.commandId, it.sessionId, CommandSubmissionPhase.FAILED, message)
        }
        pending.clear()
        return submissions
    }
}

enum class CommandSubmissionPhase { SENDING, ACCEPTED, FAILED }

data class CommandSubmission(
    val commandId: String,
    val sessionId: String,
    val phase: CommandSubmissionPhase,
    val error: String? = null,
)

data class ConnectionSnapshot(
    val host: HostProfile? = null,
    val connection: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val native: NativeState = NativeState(),
    val error: String? = null,
    val retrySeconds: Int? = null,
    val commandSubmissions: Map<String, CommandSubmission> = emptyMap(),
)

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RETRYING }

object ConnectionRuntime {
    private val mutable = MutableStateFlow(ConnectionSnapshot())
    private val cache = mutableMapOf<String, NativeState>()
    val state: StateFlow<ConnectionSnapshot> = mutable
    @Synchronized internal fun update(value: ConnectionSnapshot) { mutable.value = value }
    @Synchronized internal fun recordCommand(value: CommandSubmission) {
        mutable.value = mutable.value.copy(
            commandSubmissions = mutable.value.commandSubmissions + (value.commandId to value),
        )
    }
    @Synchronized fun consumeCommand(commandId: String) {
        mutable.value = mutable.value.copy(
            commandSubmissions = mutable.value.commandSubmissions - commandId,
        )
    }
    @Synchronized internal fun cached(hostId: String): NativeState = cache[hostId] ?: NativeState()
    @Synchronized internal fun remember(hostId: String, state: NativeState) { cache[hostId] = state }
    @Synchronized fun forget(hostId: String) { cache.remove(hostId) }
}
