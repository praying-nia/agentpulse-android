package moe.gensoukyo.agentpulse.connection

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import moe.gensoukyo.agentpulse.BuildConfig
import moe.gensoukyo.agentpulse.data.ConnectionRoute
import moe.gensoukyo.agentpulse.data.HostProfile
import moe.gensoukyo.agentpulse.protocol.PAIRING_PATH
import moe.gensoukyo.agentpulse.protocol.PAIRING_SUBPROTOCOL
import moe.gensoukyo.agentpulse.protocol.PairingBundle
import moe.gensoukyo.agentpulse.protocol.PairingCodec
import moe.gensoukyo.agentpulse.protocol.PairingRequest
import moe.gensoukyo.agentpulse.protocol.PairingServerMessage
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class PairingClient {
    suspend fun pair(
        bundle: PairingBundle,
        clientId: String,
        displayName: String,
        onPending: () -> Unit,
    ): HostProfile = withTimeout(125_000) {
        val result = CompletableDeferred<HostProfile>()
        val relay = RelayEndpoint.parse(bundle.relayEndpoint)
        val client = pinnedClient(
            bundle.serverName,
            bundle.address,
            bundle.leafSha256,
            RelayTunnelSocketFactory(relay, bundle.bootstrapToken),
        )
            .newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url("https://${bundle.serverName}:${bundle.port}$PAIRING_PATH")
            .header("Sec-WebSocket-Protocol", PAIRING_SUBPROTOCOL)
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != PAIRING_SUBPROTOCOL) {
                    result.completeExceptionally(IllegalStateException("Pairing subprotocol was not accepted"))
                    webSocket.close(1002, "subprotocol mismatch")
                    return
                }
                if (!webSocket.send(
                    PairingCodec.encodeRequest(
                        PairingRequest(
                            pairingId = bundle.pairingId,
                            bootstrapToken = bundle.bootstrapToken,
                            clientId = clientId,
                            displayName = displayName,
                            version = BuildConfig.VERSION_NAME,
                        ),
                    ),
                )) {
                    result.completeExceptionally(IllegalStateException("Pairing request could not be queued"))
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.encodeToByteArray().size > MAX_FRAME_BYTES) {
                    result.completeExceptionally(IllegalStateException("Pairing frame exceeded the 16 KiB limit"))
                    webSocket.close(1009, "frame too large")
                    return
                }
                runCatching { PairingCodec.decodeServer(text) }
                    .onSuccess { message ->
                        when (message) {
                            is PairingServerMessage.Pending -> {
                                if (message.clientId != clientId) {
                                    result.completeExceptionally(IllegalStateException("Pairing response targeted another client"))
                                    webSocket.close(1008, "client changed")
                                } else {
                                    onPending()
                                }
                            }
                            is PairingServerMessage.Succeeded -> {
                                runCatching { pairedRelayProfile(bundle, message) }
                                    .onSuccess { profile ->
                                        result.complete(profile)
                                        webSocket.close(1000, "paired")
                                    }
                                    .onFailure { error ->
                                        result.completeExceptionally(error)
                                        webSocket.close(1008, "identity changed")
                                    }
                            }
                            is PairingServerMessage.Error -> result.completeExceptionally(IllegalStateException("${message.code}: ${message.message}"))
                        }
                    }
                    .onFailure(result::completeExceptionally)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isCompleted) result.completeExceptionally(IllegalStateException("Pairing closed: $reason"))
            }
        })
        try {
            result.await()
        } finally {
            socket.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 16 * 1024
    }
}

internal fun pairedRelayProfile(
    bundle: PairingBundle,
    message: PairingServerMessage.Succeeded,
): HostProfile {
    if (
        message.hostId != bundle.hostId ||
        message.serverName != bundle.serverName ||
        message.nativeTransportVersion != 1 ||
        1 !in message.domainProtocolVersions
    ) throw IllegalStateException("Host identity or protocol changed during pairing")
    return HostProfile(
        hostId = message.hostId,
        hostName = message.hostName,
        serverName = message.serverName,
        caCertificateDer = message.caCertificateDer,
        accessToken = message.accessToken,
        lastAddress = message.nativeAddress,
        lastPort = message.nativePort,
        relayEndpoint = RelayEndpoint.parse(bundle.relayEndpoint).authority,
        selectedRoute = ConnectionRoute.RELAY,
    )
}
