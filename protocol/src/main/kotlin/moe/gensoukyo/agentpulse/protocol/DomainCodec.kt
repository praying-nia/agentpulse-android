package moe.gensoukyo.agentpulse.protocol

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object DomainCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = false }

    fun decode(text: String): DomainEnvelope = decode(json.parseToJsonElement(text))

    fun decode(element: JsonElement): DomainEnvelope {
        val root = element.objectValue("domain envelope")
        root.exact(setOf("protocol_version", "message"))
        if (root.int("protocol_version") != DOMAIN_PROTOCOL_VERSION) {
            throw ProtocolException("unsupported domain protocol version")
        }
        val message = root.objectField("message")
        message.exact(setOf("type", "payload"))
        val envelope = DomainEnvelope(message.string("type"), message.objectField("payload"))
        validate(envelope)
        return envelope
    }

    fun encode(message: DomainEnvelope): String = json.encodeToString(
        JsonElement.serializer(),
        encodeElement(message),
    )

    fun encodeElement(message: DomainEnvelope): JsonElement {
        validate(message)
        return buildJsonObject {
            put("protocol_version", DOMAIN_PROTOCOL_VERSION)
            put("message", buildJsonObject {
                put("type", message.type)
                put("payload", message.payload)
            })
        }
    }

    fun session(message: DomainEnvelope): SessionSnapshot {
        if (message.type != "agent_session") throw ProtocolException("expected agent_session")
        val payload = message.payload
        val workspace = payload.optionalObject("workspace")
        return SessionSnapshot(
            id = payload.string("id"),
            providerId = payload.string("provider_id"),
            externalId = payload.optionalString("external_id"),
            title = payload.optionalString("title"),
            workspacePath = workspace?.string("path"),
            workspaceName = workspace?.optionalString("display_name"),
            state = payload.string("state"),
            connectionState = payload.string("connection_state"),
            revision = payload.u64("revision"),
            createdAt = payload.string("created_at"),
            updatedAt = payload.string("updated_at"),
            raw = message,
        )
    }

    fun provider(message: DomainEnvelope): ProviderSummary {
        if (message.type != "provider_descriptor") throw ProtocolException("expected provider_descriptor")
        val payload = message.payload
        return ProviderSummary(
            id = payload.string("id"),
            kind = payload.string("kind"),
            displayName = payload.string("display_name"),
            version = payload.optionalString("version"),
            capabilities = payload.array("capabilities").map { it.stringValue("capability") }.toSet(),
        )
    }

    fun channelId(message: DomainEnvelope): String {
        if (message.type != "channel_descriptor") throw ProtocolException("expected channel_descriptor")
        return UuidV7.require(message.payload.string("id"), "channel.id")
    }

    fun approvalRequest(message: DomainEnvelope): ApprovalPrompt {
        if (message.type != "interaction_request") throw ProtocolException("expected interaction_request")
        return interactionRequest(message.payload)
            ?: throw ProtocolException("interaction request is not an approval")
    }

    fun approvalResponse(
        requestId: String,
        sessionId: String,
        channelId: String,
        optionId: String,
        respondedAt: Instant = Instant.now(),
    ): DomainEnvelope = DomainEnvelope(
        type = "interaction_response",
        payload = buildJsonObject {
            put("request_id", UuidV7.require(requestId, "response.request_id"))
            put("session_id", UuidV7.require(sessionId, "response.session_id"))
            put("channel_id", UuidV7.require(channelId, "response.channel_id"))
            put("responded_at", respondedAt.toString())
            put("payload", buildJsonObject {
                put("type", "approval")
                put("option_id", UuidV7.require(optionId, "approval.option_id"))
            })
        },
    ).also(::validate)

    fun event(message: DomainEnvelope): EventRecord {
        if (message.type != "agent_event") throw ProtocolException("expected agent_event")
        val payload = message.payload
        val eventPayload = payload.objectField("payload")
        val type = eventPayload.string("type")
        val sessionId = payload.string("session_id")
        val presentation = eventPresentation(type, eventPayload)
        val approval = if (type == "interaction_requested") {
            interactionRequest(eventPayload.objectField("request"))
        } else {
            null
        }
        val embeddedSessionId = when (type) {
            "session_started" -> eventPayload.objectField("session").string("id")
            "interaction_requested" -> eventPayload.objectField("request").string("session_id")
            "interaction_responded" -> eventPayload.objectField("response").string("session_id")
            "interaction_closed" -> eventPayload.objectField("interaction").string("session_id")
            "command_issued" -> eventPayload.objectField("command").string("session_id")
            else -> null
        }
        if (embeddedSessionId != null && embeddedSessionId != sessionId) {
            throw ProtocolException("event payload session mismatch")
        }
        val terminalInteractionId = when (type) {
            "interaction_responded" -> eventPayload.objectField("response").string("request_id")
            "interaction_closed" -> eventPayload.objectField("interaction").string("request_id")
            else -> null
        }
        return EventRecord(
            id = payload.string("id"),
            sessionId = sessionId,
            sequence = payload.u64("sequence"),
            occurredAt = payload.string("occurred_at"),
            type = type,
            title = presentation.first,
            detail = presentation.second,
            importance = presentation.third,
            approval = approval,
            terminalInteractionId = terminalInteractionId,
            raw = message,
        )
    }

    private fun validate(message: DomainEnvelope) {
        when (message.type) {
            "provider_descriptor" -> descriptor(message.payload, provider = true)
            "channel_descriptor" -> descriptor(message.payload, provider = false)
            "agent_session" -> sessionPayload(message.payload)
            "agent_event" -> eventPayload(message.payload)
            "interaction_request" -> interactionRequest(message.payload)
            "interaction_response" -> interactionResponse(message.payload)
            "agent_command" -> command(message.payload)
            else -> throw ProtocolException("unknown domain message type ${message.type}")
        }
    }

    private fun descriptor(value: JsonObject, provider: Boolean) {
        value.exact(
            setOf("id", "kind", "display_name", "capabilities"),
            setOf("version"),
        )
        UuidV7.require(value.string("id"), "descriptor.id")
        value.nonblank("kind")
        value.nonblank("display_name")
        value.optionalString("version")?.requireNotBlank("version")
        val allowed = if (provider) PROVIDER_CAPABILITIES else CHANNEL_CAPABILITIES
        value.array("capabilities").uniqueStrings("capabilities", allowed)
    }

    private fun sessionPayload(value: JsonObject) {
        value.exact(
            setOf(
                "id", "provider_id", "state", "connection_state", "revision", "created_at", "updated_at",
            ),
            setOf("external_id", "title", "workspace"),
        )
        UuidV7.require(value.string("id"), "session.id")
        UuidV7.require(value.string("provider_id"), "session.provider_id")
        value.optionalString("external_id")?.requireNotBlank("external_id")
        value.optionalString("title")?.requireNotBlank("title")
        value.optionalObject("workspace")?.let { workspace ->
            workspace.exact(setOf("path"), setOf("display_name"))
            workspace.nonblank("path")
            workspace.optionalString("display_name")?.requireNotBlank("workspace.display_name")
        }
        value.enum("state", AGENT_STATES)
        value.enum("connection_state", CONNECTION_STATES)
        value.u64("revision")
        value.instant("created_at")
        value.instant("updated_at")
    }

    private fun eventPayload(value: JsonObject) {
        value.exact(setOf("id", "session_id", "sequence", "occurred_at", "payload"))
        UuidV7.require(value.string("id"), "event.id")
        UuidV7.require(value.string("session_id"), "event.session_id")
        if (value.u64("sequence") == 0UL) throw ProtocolException("event.sequence must be non-zero")
        value.instant("occurred_at")
        val payload = value.objectField("payload")
        when (payload.string("type")) {
            "session_started" -> {
                payload.exact(setOf("type", "session")); sessionPayload(payload.objectField("session"))
            }
            "state_changed" -> {
                payload.exact(setOf("type", "state")); payload.enum("state", AGENT_STATES)
            }
            "connection_changed" -> {
                payload.exact(setOf("type", "connection_state")); payload.enum("connection_state", CONNECTION_STATES)
            }
            "message" -> {
                payload.exact(setOf("type", "message")); message(payload.objectField("message"))
            }
            "tool_activity" -> {
                payload.exact(setOf("type", "activity")); tool(payload.objectField("activity"))
            }
            "plan_updated" -> {
                payload.exact(setOf("type", "plan")); plan(payload.objectField("plan"))
            }
            "progress_updated" -> {
                payload.exact(setOf("type", "progress")); progress(payload.objectField("progress"))
            }
            "interaction_requested" -> {
                payload.exact(setOf("type", "request")); interactionRequest(payload.objectField("request"))
            }
            "interaction_responded" -> {
                payload.exact(setOf("type", "response")); interactionResponse(payload.objectField("response"))
            }
            "interaction_closed" -> {
                payload.exact(setOf("type", "interaction")); interactionClosed(payload.objectField("interaction"))
            }
            "command_issued" -> {
                payload.exact(setOf("type", "command")); command(payload.objectField("command"))
            }
            "session_ended" -> {
                payload.exact(setOf("type", "outcome")); outcome(payload.objectField("outcome"))
            }
            else -> throw ProtocolException("unknown event payload type ${payload.string("type")}")
        }
        val embeddedSessionId = when (payload.string("type")) {
            "session_started" -> payload.objectField("session").string("id")
            "interaction_requested" -> payload.objectField("request").string("session_id")
            "interaction_responded" -> payload.objectField("response").string("session_id")
            "interaction_closed" -> payload.objectField("interaction").string("session_id")
            "command_issued" -> payload.objectField("command").string("session_id")
            else -> null
        }
        if (embeddedSessionId != null && embeddedSessionId != value.string("session_id")) {
            throw ProtocolException("event payload session mismatch")
        }
    }

    private fun message(value: JsonObject) {
        value.exact(setOf("level", "content")); value.enum("level", MESSAGE_LEVELS); value.nonblank("content")
    }

    private fun tool(value: JsonObject) {
        when (value.string("type")) {
            "started" -> {
                value.exact(setOf("type", "call_id", "name"), setOf("summary"))
                UuidV7.require(value.string("call_id"), "tool.call_id")
                value.nonblank("name"); value.optionalString("summary")?.requireNotBlank("tool.summary")
            }
            "finished" -> {
                value.exact(setOf("type", "call_id", "outcome"), setOf("summary"))
                UuidV7.require(value.string("call_id"), "tool.call_id")
                value.enum("outcome", TOOL_OUTCOMES); value.optionalString("summary")?.requireNotBlank("tool.summary")
            }
            else -> throw ProtocolException("unknown tool activity")
        }
    }

    private fun plan(value: JsonObject) {
        value.exact(setOf("revision", "items"), setOf("explanation"))
        value.u64("revision"); value.optionalString("explanation")?.requireNotBlank("plan.explanation")
        val ids = mutableSetOf<String>()
        value.array("items").forEach { element ->
            val item = element.objectValue("plan item")
            item.exact(setOf("id", "content", "status"))
            val id = UuidV7.require(item.string("id"), "plan.item.id")
            if (!ids.add(id)) throw ProtocolException("duplicate plan item")
            item.nonblank("content"); item.enum("status", PLAN_STATUSES)
        }
    }

    private fun progress(value: JsonObject) {
        value.exact(setOf("revision", "value"), setOf("message"))
        value.u64("revision"); value.optionalString("message")?.requireNotBlank("progress.message")
        val progress = value.objectField("value")
        when (progress.string("type")) {
            "indeterminate" -> progress.exact(setOf("type"))
            "determinate" -> {
                progress.exact(setOf("type", "completed", "total"))
                val completed = progress.u64("completed"); val total = progress.u64("total")
                if (total == 0UL || completed > total) throw ProtocolException("invalid determinate progress")
            }
            else -> throw ProtocolException("unknown progress type")
        }
    }

    private fun interactionRequest(value: JsonObject): ApprovalPrompt? {
        value.exact(setOf("id", "session_id", "requested_at", "prompt", "payload"), setOf("expires_at"))
        UuidV7.require(value.string("id"), "interaction.id")
        UuidV7.require(value.string("session_id"), "interaction.session_id")
        val requestedAt = value.instant("requested_at")
        value.optionalString("expires_at")?.let(::parseInstant)?.let { expiresAt ->
            if (!expiresAt.isAfter(requestedAt)) {
                throw ProtocolException("interaction expiration must be after its request time")
            }
        }
        value.nonblank("prompt")
        val payload = value.objectField("payload")
        when (payload.string("type")) {
            "approval" -> {
                payload.exact(setOf("type", "subject", "options"), setOf("unavailable_reason"))
                val subject = approvalSubject(payload.objectField("subject"))
                val optionIds = mutableSetOf<String>()
                val options = payload.array("options").map { element ->
                    val option = element.objectValue("approval option")
                    option.exact(setOf("id", "disposition", "label"), setOf("description"))
                    val id = UuidV7.require(option.string("id"), "approval.option.id")
                    if (!optionIds.add(id)) throw ProtocolException("duplicate approval option")
                    ApprovalOption(
                        id = id,
                        disposition = option.enum("disposition", APPROVAL_DISPOSITIONS),
                        label = option.nonblank("label"),
                        description = option.optionalString("description")?.requireNotBlank("approval.description"),
                    )
                }
                val unavailable = payload.optionalString("unavailable_reason")
                    ?.requireNotBlank("approval.unavailable_reason")
                if ((unavailable == null) == options.isEmpty()) {
                    throw ProtocolException("approval must be either actionable or explicitly unavailable")
                }
                return ApprovalPrompt(
                    id = value.string("id"),
                    sessionId = value.string("session_id"),
                    requestedAt = value.string("requested_at"),
                    prompt = value.string("prompt"),
                    subject = subject,
                    options = options,
                    unavailableReason = unavailable,
                )
            }
            "choice" -> {
                payload.exact(setOf("type", "options", "multiple")); payload.boolean("multiple")
                val ids = mutableSetOf<String>()
                val options = payload.array("options")
                if (options.isEmpty()) throw ProtocolException("choice options must not be empty")
                options.forEach { element ->
                    val option = element.objectValue("choice option")
                    option.exact(setOf("id", "label"), setOf("description"))
                    val id = UuidV7.require(option.string("id"), "choice.id")
                    if (!ids.add(id)) throw ProtocolException("duplicate choice option")
                    option.nonblank("label"); option.optionalString("description")?.requireNotBlank("choice.description")
                }
            }
            "text" -> {
                payload.exact(setOf("type", "multiline"), setOf("placeholder")); payload.boolean("multiline")
                payload.optionalString("placeholder")?.requireNotBlank("text.placeholder")
            }
            else -> throw ProtocolException("unknown interaction request")
        }
        return null
    }

    private fun approvalSubject(value: JsonObject): ApprovalSubject = when (value.string("type")) {
        "command" -> {
            value.exact(setOf("type", "kind"), setOf("command", "cwd", "reason", "network"))
            val network = value.optionalObject("network")?.let {
                it.exact(setOf("host", "protocol"))
                ApprovalNetworkContext(it.nonblank("host"), it.nonblank("protocol"))
            }
            ApprovalSubject.Command(
                kind = value.enum("kind", APPROVAL_COMMAND_KINDS),
                command = value.optionalString("command")?.requireNotBlank("approval.command"),
                cwd = value.optionalString("cwd")?.requireNotBlank("approval.cwd"),
                reason = value.optionalString("reason")?.requireNotBlank("approval.reason"),
                network = network,
            )
        }
        "file_change" -> {
            value.exact(setOf("type", "changes"), setOf("grant_root", "reason"))
            ApprovalSubject.FileChange(
                changes = value.array("changes").map { element ->
                    val change = element.objectValue("file change")
                    change.exact(setOf("path", "kind", "diff"))
                    ApprovalFileChange(
                        path = change.nonblank("path"),
                        kind = change.enum("kind", APPROVAL_FILE_CHANGE_KINDS),
                        diff = change.string("diff"),
                    )
                },
                grantRoot = value.optionalString("grant_root")?.requireNotBlank("approval.grant_root"),
                reason = value.optionalString("reason")?.requireNotBlank("approval.reason"),
            )
        }
        else -> throw ProtocolException("unknown approval subject")
    }

    private fun interactionResponse(value: JsonObject) {
        value.exact(setOf("request_id", "session_id", "channel_id", "responded_at", "payload"))
        UuidV7.require(value.string("request_id"), "response.request_id")
        UuidV7.require(value.string("session_id"), "response.session_id")
        UuidV7.require(value.string("channel_id"), "response.channel_id")
        value.instant("responded_at")
        val payload = value.objectField("payload")
        when (payload.string("type")) {
            "approval" -> {
                payload.exact(setOf("type", "option_id"))
                UuidV7.require(payload.string("option_id"), "approval.option_id")
            }
            "choice" -> {
                payload.exact(setOf("type", "option_ids")); payload.array("option_ids").forEach {
                    UuidV7.require(it.stringValue("option_id"), "option_id")
                }
            }
            "text" -> {
                payload.exact(setOf("type", "text")); payload.string("text")
            }
            else -> throw ProtocolException("unknown interaction response")
        }
    }

    private fun interactionClosed(value: JsonObject) {
        value.exact(setOf("request_id", "session_id", "reason"))
        UuidV7.require(value.string("request_id"), "interaction_closed.request_id")
        UuidV7.require(value.string("session_id"), "interaction_closed.session_id")
        value.enum("reason", INTERACTION_CLOSE_REASONS)
    }

    private fun command(value: JsonObject) {
        value.exact(setOf("id", "session_id", "channel_id", "issued_at", "payload"))
        UuidV7.require(value.string("id"), "command.id")
        UuidV7.require(value.string("session_id"), "command.session_id")
        UuidV7.require(value.string("channel_id"), "command.channel_id")
        value.instant("issued_at")
        val payload = value.objectField("payload")
        when (payload.string("type")) {
            "submit_prompt" -> {
                payload.exact(setOf("type", "text")); payload.nonblank("text")
            }
            "cancel_session" -> {
                payload.exact(setOf("type"), setOf("reason")); payload.optionalString("reason")?.requireNotBlank("cancel.reason")
            }
            else -> throw ProtocolException("unknown command")
        }
    }

    private fun outcome(value: JsonObject) {
        when (value.string("type")) {
            "completed" -> {
                value.exact(setOf("type"), setOf("summary")); value.optionalString("summary")?.requireNotBlank("outcome.summary")
            }
            "failed" -> {
                value.exact(setOf("type", "error")); value.nonblank("error")
            }
            "cancelled" -> {
                value.exact(setOf("type"), setOf("reason")); value.optionalString("reason")?.requireNotBlank("outcome.reason")
            }
            else -> throw ProtocolException("unknown session outcome")
        }
    }

    private fun eventPresentation(type: String, payload: JsonObject): Triple<String, String?, EventImportance> = when (type) {
        "session_started" -> Triple("Session started", payload.objectField("session").optionalString("title"), EventImportance.NORMAL)
        "state_changed" -> Triple("State changed", payload.string("state"), EventImportance.NORMAL)
        "connection_changed" -> Triple("Connection changed", payload.string("connection_state"), EventImportance.WARNING)
        "message" -> payload.objectField("message").let {
            val level = it.string("level")
            Triple("Message · $level", it.string("content"), if (level == "error") EventImportance.ERROR else if (level == "warning") EventImportance.WARNING else EventImportance.NORMAL)
        }
        "tool_activity" -> payload.objectField("activity").let {
            Triple("Tool ${it.string("type")}", it.optionalString("summary") ?: it.optionalString("name"), EventImportance.NORMAL)
        }
        "plan_updated" -> Triple("Plan updated", payload.objectField("plan").optionalString("explanation"), EventImportance.NORMAL)
        "progress_updated" -> Triple("Progress updated", payload.objectField("progress").optionalString("message"), EventImportance.NORMAL)
        "interaction_requested" -> Triple("Approval requested", payload.objectField("request").string("prompt"), EventImportance.INTERACTION)
        "interaction_responded" -> Triple("Interaction responded", null, EventImportance.NORMAL)
        "interaction_closed" -> Triple("Interaction closed", payload.objectField("interaction").string("reason"), EventImportance.NORMAL)
        "command_issued" -> Triple("Command issued", payload.objectField("command").objectField("payload").string("type"), EventImportance.NORMAL)
        "session_ended" -> payload.objectField("outcome").let {
            Triple("Session ${it.string("type")}", it.optionalString("summary") ?: it.optionalString("error") ?: it.optionalString("reason"), EventImportance.OUTCOME)
        }
        else -> throw ProtocolException("unknown event presentation")
    }

    private val PROVIDER_CAPABILITIES = setOf("session_state", "tool_events", "plan", "progress", "approval_request", "approval_response", "user_input_request", "user_input_response", "prompt_submit", "cancel")
    private val CHANNEL_CAPABILITIES = setOf("notification", "session_view", "tool_view", "plan_view", "progress_view", "rich_message", "approval", "choice_input", "text_input", "form_input", "realtime_sync", "remote_command")
    private val AGENT_STATES = setOf("initializing", "idle", "running", "waiting_for_interaction", "completed", "failed", "cancelled")
    private val CONNECTION_STATES = setOf("connected", "reconnecting", "disconnected")
    private val MESSAGE_LEVELS = setOf("info", "warning", "error")
    private val TOOL_OUTCOMES = setOf("succeeded", "failed", "cancelled")
    private val PLAN_STATUSES = setOf("pending", "in_progress", "completed", "blocked", "skipped")
    private val APPROVAL_DISPOSITIONS = setOf("approve", "reject", "cancel")
    private val APPROVAL_COMMAND_KINDS = setOf("command", "write_stdin")
    private val APPROVAL_FILE_CHANGE_KINDS = setOf("add", "delete", "update")
    private val INTERACTION_CLOSE_REASONS = setOf("resolved_elsewhere", "provider_cancelled")
}

internal fun JsonElement.objectValue(field: String): JsonObject = this as? JsonObject ?: throw ProtocolException("$field must be an object")
internal fun JsonElement.stringValue(field: String): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ProtocolException("$field must be a string")
internal fun JsonObject.exact(required: Set<String>, optional: Set<String> = emptySet()) {
    val missing = required - keys
    val unknown = keys - required - optional
    if (missing.isNotEmpty() || unknown.isNotEmpty()) throw ProtocolException("invalid fields; missing=$missing unknown=$unknown")
}
internal fun JsonObject.string(field: String): String = get(field)?.stringValue(field) ?: throw ProtocolException("missing $field")
internal fun JsonObject.nonblank(field: String): String = string(field).requireNotBlank(field)
internal fun String.requireNotBlank(field: String): String {
    if (isBlank()) throw ProtocolException("$field must be nonblank")
    return this
}
internal fun JsonObject.optionalString(field: String): String? = when (val value = get(field)) {
    null, JsonNull -> null
    else -> value.stringValue(field)
}
internal fun JsonObject.objectField(field: String): JsonObject = get(field)?.objectValue(field) ?: throw ProtocolException("missing $field")
internal fun JsonObject.optionalObject(field: String): JsonObject? = when (val value = get(field)) {
    null, JsonNull -> null
    else -> value.objectValue(field)
}
internal fun JsonObject.array(field: String): JsonArray = get(field) as? JsonArray ?: throw ProtocolException("$field must be an array")
internal fun JsonObject.int(field: String): Int = get(field)?.jsonPrimitive?.intOrNull ?: throw ProtocolException("$field must be an integer")
internal fun JsonObject.boolean(field: String): Boolean = get(field)?.jsonPrimitive?.booleanOrNull ?: throw ProtocolException("$field must be a boolean")
internal fun JsonObject.u64(field: String): ULong {
    val text = string(field)
    val value = text.toULongOrNull() ?: throw ProtocolException("$field must be a decimal u64 string")
    if (value.toString() != text) throw ProtocolException("$field must use canonical decimal encoding")
    return value
}
internal fun JsonObject.instant(field: String): Instant = parseInstant(string(field))
internal fun parseInstant(value: String): Instant = try { Instant.parse(value) } catch (error: Exception) { throw ProtocolException("invalid timestamp", error) }
internal fun JsonObject.enum(field: String, allowed: Set<String>): String = string(field).also { if (it !in allowed) throw ProtocolException("unknown $field value $it") }
internal fun JsonArray.uniqueStrings(field: String, allowed: Set<String>): Set<String> {
    val values = map { it.stringValue(field) }
    if (values.toSet().size != values.size || values.any { it !in allowed }) throw ProtocolException("invalid $field values")
    return values.toSet()
}
