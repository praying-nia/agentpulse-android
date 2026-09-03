package moe.gensoukyo.agentpulse.protocol

import kotlinx.serialization.json.JsonObject

const val DOMAIN_PROTOCOL_VERSION: Int = 2
const val NATIVE_TRANSPORT_VERSION: Int = 3
const val PAIRING_PROTOCOL_VERSION: Int = 1
const val NATIVE_PATH: String = "/agentpulse/native/v3"
const val NATIVE_SUBPROTOCOL: String = "agentpulse.native.v3"
const val PAIRING_PATH: String = "/agentpulse/pair/v1"
const val PAIRING_SUBPROTOCOL: String = "agentpulse.pair.v1"

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
    val messageRole: String? = null,
    val approval: ApprovalPrompt? = null,
    val form: FormPrompt? = null,
    val terminalInteractionId: String? = null,
    val raw: DomainEnvelope,
)

data class FormOption(val id: String, val label: String, val description: String?)

data class FormField(
    val id: String,
    val header: String,
    val prompt: String,
    val options: List<FormOption>,
    val allowsOther: Boolean,
    val sensitive: Boolean,
)

sealed interface FormAnswer {
    data class Choice(val optionId: String) : FormAnswer
    data class Text(val text: String) : FormAnswer
}

data class FormPrompt(
    val id: String,
    val sessionId: String,
    val requestedAt: String,
    val prompt: String,
    val fields: List<FormField>,
    val blocking: Boolean,
    val interactive: Boolean = false,
    val submissionState: ApprovalSubmissionState = ApprovalSubmissionState.READY,
    val submissionError: String? = null,
)

sealed interface AgentCommandPayload {
    data class SubmitPrompt(val text: String, val steer: Boolean = false) : AgentCommandPayload
    data object Cancel : AgentCommandPayload
    data object ListModels : AgentCommandPayload
    data class SelectModel(val model: String, val effort: String? = null) : AgentCommandPayload
    data class SetPlanMode(val enabled: Boolean) : AgentCommandPayload
    data class ListThreads(val cursor: String? = null) : AgentCommandPayload
    data class ResumeThread(val threadId: String) : AgentCommandPayload
    data class StartThread(val cwd: String) : AgentCommandPayload
    data object Compact : AgentCommandPayload
    data class Review(val instructions: String? = null) : AgentCommandPayload
    data class Rename(val name: String) : AgentCommandPayload
    data object Fork : AgentCommandPayload
    data object Status : AgentCommandPayload
    data object ListPermissionProfiles : AgentCommandPayload
    data class SelectPermissionProfile(val profile: String) : AgentCommandPayload
    data class Queue(val action: String) : AgentCommandPayload
}

sealed interface ApprovalSubject {
    data class Command(
        val kind: String,
        val command: String?,
        val cwd: String?,
        val reason: String?,
        val network: ApprovalNetworkContext?,
    ) : ApprovalSubject

    data class FileChange(
        val changes: List<ApprovalFileChange>,
        val grantRoot: String?,
        val reason: String?,
    ) : ApprovalSubject
}

data class ApprovalNetworkContext(val host: String, val protocol: String)

data class ApprovalFileChange(val path: String, val kind: String, val diff: String)

data class ApprovalOption(
    val id: String,
    val disposition: String,
    val label: String,
    val description: String?,
)

enum class ApprovalSubmissionState { READY, SUBMITTING, FAILED }

data class ApprovalPrompt(
    val id: String,
    val sessionId: String,
    val requestedAt: String,
    val prompt: String,
    val subject: ApprovalSubject,
    val options: List<ApprovalOption>,
    val unavailableReason: String?,
    val interactive: Boolean = false,
    val submissionState: ApprovalSubmissionState = ApprovalSubmissionState.READY,
    val submissionError: String? = null,
)

sealed interface NativeClientMessage {
    data class Hello(
        val clientId: String,
        val displayName: String,
        val version: String?,
        val supportedProtocolVersions: List<Int> = listOf(DOMAIN_PROTOCOL_VERSION),
        val hostRunId: String? = null,
        val sessionCursors: Map<String, ULong> = emptyMap(),
    ) : NativeClientMessage

    data class Discover(val requestId: String) : NativeClientMessage
    data class Subscribe(val requestId: String, val sessionId: String) : NativeClientMessage
    data class Unsubscribe(val requestId: String, val sessionId: String) : NativeClientMessage
    data class SubmitInteractionResponse(
        val requestId: String,
        val response: DomainEnvelope,
    ) : NativeClientMessage
    data class SubmitCommand(
        val requestId: String,
        val command: DomainEnvelope,
    ) : NativeClientMessage
}

sealed interface NativeDeliveryContext {
    data class DiscoveryProvider(val requestId: String) : NativeDeliveryContext
    data class DiscoverySession(val requestId: String, val lastSequence: ULong) : NativeDeliveryContext
    data class SubscriptionSession(val requestId: String) : NativeDeliveryContext
    data class SubscriptionInteraction(val requestId: String, val route: String) : NativeDeliveryContext
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
        val hostRunId: String = "01890f47-7c00-7000-8000-000000000011",
        val resumeAccepted: Boolean = false,
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
        val pendingInteractionCount: Int,
        val eventCount: Int = 0,
        val reset: Boolean = false,
    ) : NativeServerMessage

    data class UnsubscriptionResult(
        val requestId: String,
        val sessionId: String,
        val status: String,
    ) : NativeServerMessage

    data class InteractionResponseResult(
        val requestId: String,
        val sessionId: String,
        val interactionId: String,
    ) : NativeServerMessage

    data class CommandResult(
        val requestId: String,
        val sessionId: String,
        val commandId: String,
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
    val relayEndpoint: String,
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
    val pendingApprovals: Map<String, ApprovalPrompt> = emptyMap(),
    val pendingForms: Map<String, FormPrompt> = emptyMap(),
)

data class NativeState(
    val phase: Phase = Phase.AWAITING_HELLO,
    val providers: Map<String, ProviderSummary> = emptyMap(),
    val sessions: Map<String, SessionView> = emptyMap(),
    val channelId: String? = null,
    val lastError: String? = null,
    val hostRunId: String? = null,
) {
    enum class Phase { AWAITING_HELLO, DISCOVERING, SUBSCRIBING, LIVE, FAILED }
}
