package moe.gensoukyo.agentpulse.protocol

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object PairingCodec {
    private const val URI_PREFIX = "agentpulse://pair/v1/"
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = false }

    fun decodeUri(uri: String, nowUnixSeconds: Long = System.currentTimeMillis() / 1_000): PairingBundle {
        val encoded = uri.removePrefix(URI_PREFIX)
        if (encoded == uri || encoded.isBlank()) throw ProtocolException("unsupported pairing URI")
        val bytes = try { Base64.getUrlDecoder().decode(encoded) } catch (error: IllegalArgumentException) { throw ProtocolException("invalid pairing URI", error) }
        val value = json.parseToJsonElement(bytes.decodeToString()).objectValue("pairing bundle")
        value.exact(setOf("pairing_version", "pairing_id", "host_id", "host_name", "server_name", "address", "port", "leaf_sha256", "bootstrap_token", "expires_at_unix_seconds"))
        if (value.int("pairing_version") != PAIRING_PROTOCOL_VERSION) throw ProtocolException("unsupported pairing version")
        val port = integer(value, "port", 1..65535)
        val expires = value["expires_at_unix_seconds"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: throw ProtocolException("invalid pairing expiry")
        if (expires <= nowUnixSeconds) throw ProtocolException("pairing session expired")
        val fingerprint = value.string("leaf_sha256")
        if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) throw ProtocolException("invalid leaf certificate fingerprint")
        return PairingBundle(
            pairingId = UuidV7.require(value.string("pairing_id"), "pairing_id"),
            hostId = UuidV7.require(value.string("host_id"), "host_id"),
            hostName = value.nonblank("host_name"),
            serverName = value.nonblank("server_name"),
            address = value.nonblank("address"),
            port = port,
            leafSha256 = fingerprint,
            bootstrapToken = value.nonblank("bootstrap_token"),
            expiresAtUnixSeconds = expires,
        )
    }

    fun encodeRequest(request: PairingRequest): String {
        UuidV7.require(request.pairingId, "pairing_id"); UuidV7.require(request.clientId, "client_id")
        request.bootstrapToken.requireNotBlank("bootstrap_token"); request.displayName.requireNotBlank("display_name")
        val message = buildJsonObject {
            put("type", "pair_request")
            put("pairing_id", request.pairingId)
            put("bootstrap_token", request.bootstrapToken)
            put("client_id", request.clientId)
            put("display_name", request.displayName)
            request.version?.let { put("version", it.requireNotBlank("version")) }
        }
        return json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("pairing_version", PAIRING_PROTOCOL_VERSION)
            put("message", message)
        })
    }

    fun decodeServer(text: String): PairingServerMessage {
        val root = json.parseToJsonElement(text).objectValue("pairing envelope")
        root.exact(setOf("pairing_version", "message"))
        if (root.int("pairing_version") != PAIRING_PROTOCOL_VERSION) throw ProtocolException("unsupported pairing version")
        val message = root.objectField("message")
        return when (message.string("type")) {
            "pairing_pending" -> {
                message.exact(setOf("type", "client_id", "display_name"))
                PairingServerMessage.Pending(UuidV7.require(message.string("client_id"), "client_id"), message.nonblank("display_name"))
            }
            "pairing_succeeded" -> success(message)
            "pairing_error" -> {
                message.exact(setOf("type", "code", "message", "recoverable"))
                val code = message.enum("code", setOf("invalid_request", "invalid_credential", "expired", "used", "denied", "capacity", "internal"))
                PairingServerMessage.Error(code, message.nonblank("message"), message.boolean("recoverable"))
            }
            else -> throw ProtocolException("unknown pairing server message")
        }
    }

    private fun success(message: JsonObject): PairingServerMessage.Succeeded {
        message.exact(setOf("type", "host_id", "host_name", "ca_certificate_der", "server_name", "native_address", "native_port", "access_token", "native_transport_version", "domain_protocol_versions"))
        val nativeVersion = integer(message, "native_transport_version", 1..Int.MAX_VALUE)
        val versions = message.array("domain_protocol_versions").map { element ->
            (element as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: throw ProtocolException("invalid domain protocol version")
        }
        if (versions.isEmpty() || versions.distinct().size != versions.size) throw ProtocolException("invalid domain protocol versions")
        return PairingServerMessage.Succeeded(
            hostId = UuidV7.require(message.string("host_id"), "host_id"),
            hostName = message.nonblank("host_name"),
            caCertificateDer = message.nonblank("ca_certificate_der"),
            serverName = message.nonblank("server_name"),
            nativeAddress = message.nonblank("native_address"),
            nativePort = integer(message, "native_port", 1..65535),
            accessToken = message.nonblank("access_token"),
            nativeTransportVersion = nativeVersion,
            domainProtocolVersions = versions,
        )
    }

    private fun integer(value: JsonObject, field: String, range: IntRange): Int = value[field]?.let { (it as? JsonPrimitive)?.longOrNull }?.takeIf { it in range.first.toLong()..range.last.toLong() }?.toInt() ?: throw ProtocolException("invalid $field")
}
