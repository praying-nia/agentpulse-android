package moe.gensoukyo.agentpulse.connection

import java.util.concurrent.atomic.AtomicBoolean
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
import moe.gensoukyo.agentpulse.protocol.NativeState
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class NativeWebSocketClient(
    private val profile: HostProfile,
    private val clientId: String,
    private val onState: (NativeState) -> Unit,
    private val onEventState: (NativeState, NativeState) -> Unit,
) {
    private val completion = CompletableDeferred<Throwable?>()
    private val reducer = NativeSessionReducer()
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
    private var socket: WebSocket? = null

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
                runCatching {
                    val before = reducer.state
                    val outgoing = reducer.accept(NativeCodec.decode(text))
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

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish(t)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(null)
        })
    }

    suspend fun awaitClose(): Throwable? = completion.await()

    fun close() {
        socket?.close(1000, "user disconnected")
        socket = null
        finish(null)
    }

    private fun finish(error: Throwable?) {
        if (!finished.compareAndSet(false, true)) return
        completion.complete(error)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val MAX_FRAME_BYTES = 1024 * 1024
    }
}

data class ConnectionSnapshot(
    val host: HostProfile? = null,
    val connection: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val native: NativeState = NativeState(),
    val error: String? = null,
    val retrySeconds: Int? = null,
)

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RETRYING }

object ConnectionRuntime {
    private val mutable = MutableStateFlow(ConnectionSnapshot())
    val state: StateFlow<ConnectionSnapshot> = mutable
    internal fun update(value: ConnectionSnapshot) { mutable.value = value }
}
