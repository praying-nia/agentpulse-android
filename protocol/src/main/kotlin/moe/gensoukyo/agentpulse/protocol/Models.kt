package moe.gensoukyo.agentpulse.protocol

import kotlinx.serialization.json.JsonObject

const val DOMAIN_PROTOCOL_VERSION: Int = 1
const val NATIVE_TRANSPORT_VERSION: Int = 1
const val PAIRING_PROTOCOL_VERSION: Int = 1
const val NATIVE_PATH: String = "/agentpulse/native/v1"
const val NATIVE_SUBPROTOCOL: String = "agentpulse.native.v1"
const val PAIRING_PATH: String = "/agentpulse/pair/v1"
const val PAIRING_SUBPROTOCOL: String = "agentpulse.pair.v1"
const val PAIRING_BLE_SERVICE_UUID: String = "d22e50f9-015e-53ba-be49-3e4d235f3288"
const val PAIRING_BLE_CHARACTERISTIC_UUID: String = "ea63bfc9-87c3-5074-aa37-49b6a617569b"

class ProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

data class DomainEnvelope(
    val type: String,
    val payload: JsonObject,
)

data class ProviderSummary(
    val id: String,
    val kind: String,
    val displayName: String,
    val version: String?,
    val capabilities: Set<String>,
)

data class SessionSnapshot(
    val id: String,
    val providerId: String,
    val externalId: String?,
    val title: String?,
    val workspacePath: String?,
    val workspaceName: String?,
    val state: String,
    val connectionState: String,
    val revision: ULong,
    val createdAt: String,
    val updatedAt: String,
    val raw: DomainEnvelope,
)

enum class EventImportance { NORMAL, WARNING, ERROR, INTERACTION, OUTCOME }

data class EventRecord(
    val id: String,
    val sessionId: String,
    val sequence: ULong,
    val occurredAt: String,
    val type: String,
    val title: String,
    val detail: String?,
    val importance: EventImportance,
    val raw: DomainEnvelope,
)

sealed interface NativeClientMessage {
    data class Hello(
        val clientId: String,
        val displayName: String,
        val version: String?,
        val supportedProtocolVersions: List<Int> = listOf(DOMAIN_PROTOCOL_VERSION),
    ) : NativeClientMessage

    data class Discover(val requestId: String) : NativeClientMessage
    data class Subscribe(val requestId: String, val sessionId: String) : NativeClientMessage
    data class Unsubscribe(val requestId: String, val sessionId: String) : NativeClientMessage
}

sealed interface NativeDeliveryContext {
    data class DiscoveryProvider(val requestId: String) : NativeDeliveryContext
    data class DiscoverySession(val requestId: String, val lastSequence: ULong) : NativeDeliveryContext
    data class SubscriptionSession(val requestId: String) : NativeDeliveryContext
    data class LiveEvent(val route: String) : NativeDeliveryContext
    data object LiveSession : NativeDeliveryContext
}

sealed interface NativeServerMessage {
    data class Hello(
        val connectionId: String,
        val channel: DomainEnvelope,
        val protocolVersion: Int,
        val maxFrameBytes: Int,
        val pingIntervalSeconds: Long,
        val idleTimeoutSeconds: Long,
    ) : NativeServerMessage

    data class SyncStarted(
        val requestId: String,
        val providerCount: Int,
        val sessionCount: Int,
    ) : NativeServerMessage

    data class Domain(
        val context: NativeDeliveryContext,
        val domain: DomainEnvelope,
    ) : NativeServerMessage

    data class SyncCompleted(val requestId: String) : NativeServerMessage

    data class SubscriptionResult(
        val requestId: String,
        val sessionId: String,
        val status: String,
        val baselineSequence: ULong,
    ) : NativeServerMessage

    data class UnsubscriptionResult(
        val requestId: String,
        val sessionId: String,
        val status: String,
    ) : NativeServerMessage

    data class Error(
        val requestId: String?,
        val code: String,
        val message: String,
        val recoverable: Boolean,
    ) : NativeServerMessage
}

data class PairingBundle(
    val pairingId: String,
    val hostId: String,
    val hostName: String,
    val serverName: String,
    val address: String,
    val port: Int,
    val leafSha256: String,
    val bootstrapToken: String,
    val expiresAtUnixSeconds: Long,
)

data class PairingRequest(
    val pairingId: String,
    val bootstrapToken: String,
    val clientId: String,
    val displayName: String,
    val version: String?,
)

sealed interface PairingServerMessage {
    data class Pending(val clientId: String, val displayName: String) : PairingServerMessage

    data class Succeeded(
        val hostId: String,
        val hostName: String,
        val caCertificateDer: String,
        val serverName: String,
        val nativeAddress: String,
        val nativePort: Int,
        val accessToken: String,
        val nativeTransportVersion: Int,
        val domainProtocolVersions: List<Int>,
    ) : PairingServerMessage

    data class Error(val code: String, val message: String, val recoverable: Boolean) : PairingServerMessage
}

data class SessionView(
    val session: SessionSnapshot,
    val cursor: ULong,
    val events: List<EventRecord>,
)

data class NativeState(
    val phase: Phase = Phase.AWAITING_HELLO,
    val providers: Map<String, ProviderSummary> = emptyMap(),
    val sessions: Map<String, SessionView> = emptyMap(),
    val lastError: String? = null,
) {
    enum class Phase { AWAITING_HELLO, DISCOVERING, SUBSCRIBING, LIVE, FAILED }
}
