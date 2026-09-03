package moe.gensoukyo.agentpulse.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object NativeCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = false }

    fun encode(message: NativeClientMessage): String {
        val body = when (message) {
            is NativeClientMessage.Hello -> {
                UuidV7.require(message.clientId, "client_id")
                message.displayName.requireNotBlank("display_name")
                if (message.supportedProtocolVersions.distinct().size != message.supportedProtocolVersions.size || message.supportedProtocolVersions.any { it <= 0 }) {
                    throw ProtocolException("supported protocol versions must be unique and positive")
                }
                if (message.hostRunId == null && message.sessionCursors.isNotEmpty()) {
                    throw ProtocolException("Session cursors require host_run_id")
                }
                buildJsonObject {
                    put("type", "client_hello")
                    put("client_id", message.clientId)
                    put("display_name", message.displayName)
                    message.version?.let { put("version", it.requireNotBlank("version")) }
                    put(
                        "supported_protocol_versions",
                        buildJsonArray {
                            message.supportedProtocolVersions.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                    message.hostRunId?.let { put("host_run_id", UuidV7.require(it, "host_run_id")) }
                    if (message.sessionCursors.isNotEmpty()) {
                        put("session_cursors", buildJsonArray {
                            message.sessionCursors.toSortedMap().forEach { (sessionId, sequence) ->
                                if (sequence == 0UL) throw ProtocolException("Session cursor must be positive")
                                add(buildJsonObject {
                                    put("session_id", UuidV7.require(sessionId, "session_id"))
                                    put("last_sequence", sequence.toString())
                                })
                            }
                        })
                    }
                }
            }
            is NativeClientMessage.Discover -> request("discover_sessions", message.requestId)
            is NativeClientMessage.Subscribe -> sessionRequest("subscribe_session", message.requestId, message.sessionId)
            is NativeClientMessage.Unsubscribe -> sessionRequest("unsubscribe_session", message.requestId, message.sessionId)
            is NativeClientMessage.SubmitInteractionResponse -> {
                UuidV7.require(message.requestId, "request_id")
                if (message.response.type != "interaction_response") {
                    throw ProtocolException("submit_interaction_response requires an interaction_response domain message")
                }
                buildJsonObject {
                    put("type", "submit_interaction_response")
                    put("request_id", message.requestId)
                    put("response", DomainCodec.encodeElement(message.response))
                }
            }
            is NativeClientMessage.SubmitCommand -> {
                UuidV7.require(message.requestId, "request_id")
                if (message.command.type != "agent_command") throw ProtocolException("submit_command requires an agent_command domain message")
                buildJsonObject {
                    put("type", "submit_command")
                    put("request_id", message.requestId)
                    put("command", DomainCodec.encodeElement(message.command))
                }
            }
        }
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("native_transport_version", NATIVE_TRANSPORT_VERSION)
            put("message", body)
        })
    }

    fun decode(text: String): NativeServerMessage {
        val root = json.parseToJsonElement(text).objectValue("native envelope")
        root.exact(setOf("native_transport_version", "message"))
        if (root.int("native_transport_version") != NATIVE_TRANSPORT_VERSION) throw ProtocolException("unsupported Native Transport version")
        val message = root.objectField("message")
        return when (message.string("type")) {
            "server_hello" -> decodeHello(message)
            "sync_started" -> {
                message.exact(setOf("type", "request_id", "provider_count", "session_count"))
                NativeServerMessage.SyncStarted(
                    requestId = uuid(message, "request_id"),
                    providerCount = nonnegativeInt(message, "provider_count"),
                    sessionCount = nonnegativeInt(message, "session_count"),
                )
            }
            "domain_message" -> {
                message.exact(setOf("type", "context", "domain"))
                NativeServerMessage.Domain(decodeContext(message.objectField("context")), DomainCodec.decode(message.getValue("domain")))
            }
            "sync_completed" -> {
                message.exact(setOf("type", "request_id")); NativeServerMessage.SyncCompleted(uuid(message, "request_id"))
            }
            "subscription_result" -> {
                message.exact(setOf("type", "request_id", "session_id", "status", "baseline_sequence", "pending_interaction_count", "event_count", "reset"))
                NativeServerMessage.SubscriptionResult(
                    uuid(message, "request_id"),
                    uuid(message, "session_id"),
                    message.enum("status", setOf("catching_up", "subscribed", "already_subscribed")),
                    message.u64("baseline_sequence"),
                    nonnegativeInt(message, "pending_interaction_count"),
                    nonnegativeInt(message, "event_count"),
                    message.boolean("reset"),
                )
            }
            "unsubscription_result" -> {
                message.exact(setOf("type", "request_id", "session_id", "status"))
                NativeServerMessage.UnsubscriptionResult(
                    uuid(message, "request_id"),
                    uuid(message, "session_id"),
                    message.enum("status", setOf("unsubscribed", "not_subscribed")),
                )
            }
            "interaction_response_result" -> {
                message.exact(setOf("type", "request_id", "session_id", "interaction_id"))
                NativeServerMessage.InteractionResponseResult(
                    uuid(message, "request_id"),
                    uuid(message, "session_id"),
                    uuid(message, "interaction_id"),
                )
            }
            "command_result" -> {
                message.exact(setOf("type", "request_id", "session_id", "command_id"))
                NativeServerMessage.CommandResult(uuid(message, "request_id"), uuid(message, "session_id"), uuid(message, "command_id"))
            }
            "error" -> decodeError(message)
            else -> throw ProtocolException("unknown Native server message ${message.string("type")}")
        }
    }

    private fun decodeHello(message: JsonObject): NativeServerMessage.Hello {
        message.exact(setOf("type", "connection_id", "channel", "protocol_version", "max_frame_bytes", "ping_interval_seconds", "idle_timeout_seconds", "host_run_id", "resume_accepted"))
        val channel = DomainCodec.decode(message.getValue("channel"))
        if (channel.type != "channel_descriptor") throw ProtocolException("server hello channel must be a channel descriptor")
        val capabilities = channel.payload.array("capabilities").map { it.stringValue("capability") }.toSet()
        if (capabilities != setOf("notification", "session_view", "approval", "form_input", "text_input", "realtime_sync", "remote_command")) {
            throw ProtocolException("Native v3 channel capabilities are incompatible")
        }
        val protocolVersion = message.int("protocol_version")
        if (protocolVersion != DOMAIN_PROTOCOL_VERSION) throw ProtocolException("server selected unsupported domain protocol")
        val max = positiveInt(message, "max_frame_bytes")
        val ping = positiveLong(message, "ping_interval_seconds")
        val idle = positiveLong(message, "idle_timeout_seconds")
        if (idle <= ping) throw ProtocolException("idle timeout must exceed ping interval")
        return NativeServerMessage.Hello(
            uuid(message, "connection_id"),
            channel,
            protocolVersion,
            max,
            ping,
            idle,
            uuid(message, "host_run_id"),
            message.boolean("resume_accepted"),
        )
    }

    private fun decodeContext(context: JsonObject): NativeDeliveryContext = when (context.string("type")) {
        "discovery_provider" -> {
            context.exact(setOf("type", "request_id")); NativeDeliveryContext.DiscoveryProvider(uuid(context, "request_id"))
        }
        "discovery_session" -> {
            context.exact(setOf("type", "request_id", "last_sequence")); NativeDeliveryContext.DiscoverySession(uuid(context, "request_id"), context.u64("last_sequence"))
        }
        "subscription_session" -> {
            context.exact(setOf("type", "request_id")); NativeDeliveryContext.SubscriptionSession(uuid(context, "request_id"))
        }
        "subscription_interaction" -> {
            context.exact(setOf("type", "request_id", "route"))
            NativeDeliveryContext.SubscriptionInteraction(
                uuid(context, "request_id"),
                context.enum("route", EVENT_ROUTES),
            )
        }
        "live_event" -> {
            context.exact(setOf("type", "route")); NativeDeliveryContext.LiveEvent(context.enum("route", EVENT_ROUTES))
        }
        "live_session" -> {
            context.exact(setOf("type")); NativeDeliveryContext.LiveSession
        }
        else -> throw ProtocolException("unknown Native delivery context")
    }

    private fun decodeError(message: JsonObject): NativeServerMessage.Error {
        message.exact(setOf("type", "code", "message", "recoverable"), setOf("request_id"))
        val requestId = when (val value = message["request_id"]) {
            null, JsonNull -> null
            else -> UuidV7.require(value.stringValue("request_id"), "request_id")
        }
        val allowed = setOf(
            "connection_busy", "invalid_handshake", "invalid_request", "session_not_discovered",
            "session_not_found", "internal", "capability_unavailable",
            "interaction_not_pending", "session_not_subscribed", "provider_rejected",
        )
        return NativeServerMessage.Error(requestId, message.enum("code", allowed), message.nonblank("message"), message.boolean("recoverable"))
    }

    private fun request(type: String, requestId: String): JsonObject {
        UuidV7.require(requestId, "request_id")
        return buildJsonObject { put("type", type); put("request_id", requestId) }
    }

    private fun sessionRequest(type: String, requestId: String, sessionId: String): JsonObject {
        UuidV7.require(requestId, "request_id"); UuidV7.require(sessionId, "session_id")
        return buildJsonObject { put("type", type); put("request_id", requestId); put("session_id", sessionId) }
    }

    private fun uuid(value: JsonObject, field: String): String = UuidV7.require(value.string(field), field)
    private fun nonnegativeInt(value: JsonObject, field: String): Int = value[field]?.let { (it as? JsonPrimitive)?.longOrNull }?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: throw ProtocolException("$field must be a nonnegative integer")
    private fun positiveInt(value: JsonObject, field: String): Int = nonnegativeInt(value, field).takeIf { it > 0 } ?: throw ProtocolException("$field must be positive")
    private fun positiveLong(value: JsonObject, field: String): Long = value[field]?.let { (it as? JsonPrimitive)?.longOrNull }?.takeIf { it > 0 } ?: throw ProtocolException("$field must be positive")

    private val EVENT_ROUTES = setOf("observe_only", "interaction_read_only", "interaction_interactive")
}
