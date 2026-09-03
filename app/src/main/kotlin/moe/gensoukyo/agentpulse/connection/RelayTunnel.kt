package moe.gensoukyo.agentpulse.connection

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal data class RelayEndpoint(val host: String, val port: Int) {
    val authority: String get() = "$host:$port"

    companion object {
        fun parse(input: String): RelayEndpoint {
            val value = input.trim()
            require(!value.contains("//") && value.none { it in "/?#@" }) {
                "Relay endpoint must be a DNS name and port without a scheme or path"
            }
            val separator = value.lastIndexOf(':')
            require(separator > 0 && separator == value.indexOf(':')) {
                "Relay endpoint must use host:port"
            }
            val host = value.substring(0, separator).lowercase()
            val port = value.substring(separator + 1).toIntOrNull()
            require(port != null && port in 1..65_535) { "Relay endpoint port is invalid" }
            require(host.length <= 253 && host.contains('.') && host.all { it.code in 1..127 }) {
                "Relay endpoint must use a public ASCII DNS name"
            }
            require(!IPV4.matches(host)) { "Relay endpoint must use DNS, not an IP address" }
            require(host.split('.').all { label ->
                label.isNotEmpty() &&
                    label.length <= 63 &&
                    !label.startsWith('-') &&
                    !label.endsWith('-') &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            }) { "Relay endpoint contains an invalid DNS label" }
            return RelayEndpoint(host, port)
        }

        private val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
    }
}

internal data class RelayRouteSecrets(
    val routeId: String,
    val authenticationKey: ByteArray,
)

internal object RelayProtocol {
    private val json = Json { ignoreUnknownKeys = false }
    private val routeDomain = "agentpulse.relay.v1.route\u0000".encodeToByteArray()
    private val clientAuthDomain = "agentpulse.relay.v1.client-auth\u0000".encodeToByteArray()
    private val proofDomain = "agentpulse.relay.v1.proof\u0000".encodeToByteArray()

    fun derive(accessToken: String, endpoint: RelayEndpoint): RelayRouteSecrets {
        val root = MessageDigest.getInstance("SHA-256").digest(accessToken.encodeToByteArray())
        val authority = endpoint.authority.encodeToByteArray()
        return RelayRouteSecrets(
            routeId = encode(hmac(root, routeDomain, authority)),
            authenticationKey = hmac(root, clientAuthDomain, authority),
        )
    }

    fun clientHello(
        accessToken: String,
        endpoint: RelayEndpoint,
        connectionId: String,
        nonce: ByteArray,
        expiresAtUnixSeconds: Long,
    ): ByteArray {
        requireUuidV7(connectionId, "connection_id")
        require(nonce.size == 32) { "Relay nonce must contain 32 bytes" }
        val route = derive(accessToken, endpoint)
        val proof = hmac(
            route.authenticationKey,
            proofPrefix(2, connectionId, nonce, expiresAtUnixSeconds),
            decode(route.routeId),
        )
        val envelope = buildJsonObject {
            put("relay_version", RELAY_VERSION)
            putJsonObject("message") {
                put("type", "client_hello")
                put("route_id", route.routeId)
                put("proof", encode(proof))
            }
        }
        return envelope.toString().encodeToByteArray()
    }

    fun openTunnel(endpoint: RelayEndpoint, accessToken: String, connectTimeoutMillis: Int): SSLSocket {
        val tcp = Socket()
        try {
            tcp.connect(
                InetSocketAddress(endpoint.host, endpoint.port),
                connectTimeoutMillis.coerceIn(1, CONNECT_TIMEOUT_MILLIS),
            )
            val outer = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(tcp, endpoint.host, endpoint.port, true) as SSLSocket
            outer.sslParameters = outer.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(endpoint.host))
            }
            outer.soTimeout = CONTROL_TIMEOUT_MILLIS
            outer.startHandshake()
            Log.d(LOG_TAG, "Relay TLS handshake completed")
            val input = DataInputStream(outer.inputStream)
            val output = DataOutputStream(outer.outputStream)
            val challenge = readMessage(input)
            challenge.requireKeys("type", "connection_id", "nonce", "expires_at_unix_seconds")
            require(challenge.string("type") == "challenge") { "Relay did not begin with a challenge" }
            val connectionId = challenge.string("connection_id")
            requireUuidV7(connectionId, "connection_id")
            val nonce = decode(challenge.string("nonce"))
            require(nonce.size == 32) { "Relay nonce must contain 32 bytes" }
            val expiry = challenge.getValue("expires_at_unix_seconds").jsonPrimitive.long
            val now = System.currentTimeMillis() / 1_000
            require(expiry > now && expiry <= now + MAX_CHALLENGE_FUTURE_SECONDS) {
                "Relay challenge is expired or unbounded"
            }
            writeFrame(output, clientHello(accessToken, endpoint, connectionId, nonce, expiry))
            val response = readMessage(input)
            when (response.string("type")) {
                "tunnel_ready" -> {
                    response.requireKeys("type", "peer_connection_id")
                    requireUuidV7(response.string("peer_connection_id"), "peer_connection_id")
                    Log.d(LOG_TAG, "Relay tunnel is ready")
                }
                "error" -> {
                    response.requireKeys("type", "code", "message", "recoverable")
                    throw IOException("Relay ${response.string("code")}: ${response.string("message")}")
                }
                else -> throw IOException("Relay returned an unexpected control message")
            }
            return outer
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Relay tunnel failed", error)
            runCatching { tcp.close() }
            if (error is IOException) throw error
            throw IOException("Relay handshake failed: ${error.message}", error)
        }
    }

    private fun readMessage(input: DataInputStream): JsonObject {
        val length = input.readInt()
        require(length in 1..MAX_CONTROL_BYTES) { "Relay control frame length is invalid" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val envelope = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        envelope.requireKeys("relay_version", "message")
        require(envelope.getValue("relay_version").jsonPrimitive.long == RELAY_VERSION.toLong()) {
            "Unsupported Relay version"
        }
        return envelope.getValue("message").jsonObject
    }

    private fun writeFrame(output: DataOutputStream, payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= MAX_CONTROL_BYTES)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun proofPrefix(role: Int, connectionId: String, nonce: ByteArray, expiry: Long): ByteArray =
        ByteBuffer.allocate(proofDomain.size + 1 + 16 + 32 + 8)
            .put(proofDomain)
            .put(role.toByte())
            .put(uuidBytes(connectionId))
            .put(nonce)
            .putLong(expiry)
            .array()

    private fun hmac(key: ByteArray, vararg values: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            values.forEach(::update)
            doFinal()
        }

    private fun uuidBytes(value: String): ByteArray = UUID.fromString(value).let { uuid ->
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }

    private fun requireUuidV7(value: String, field: String) {
        val uuid = runCatching { UUID.fromString(value) }.getOrElse {
            throw IllegalArgumentException("$field must be a UUIDv7", it)
        }
        require(uuid.version() == 7 && uuid.toString() == value.lowercase()) { "$field must be a canonical UUIDv7" }
    }

    private fun JsonObject.requireKeys(vararg names: String) {
        require(keys == names.toSet()) { "Relay message contains unexpected fields" }
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    private const val RELAY_VERSION = 1
    private const val LOG_TAG = "AgentPulseRelay"
    private const val MAX_CONTROL_BYTES = 16 * 1024
    private const val CONNECT_TIMEOUT_MILLIS = 5_000
    private const val CONTROL_TIMEOUT_MILLIS = 10_000
    private const val MAX_CHALLENGE_FUTURE_SECONDS = 30
}

internal class RelayTunnelSocketFactory(
    private val endpoint: RelayEndpoint,
    private val accessToken: String,
) : SocketFactory() {
    override fun createSocket(): Socket = RelayTunnelSocket(endpoint, accessToken)

    override fun createSocket(host: String, port: Int): Socket = connected(InetSocketAddress(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = connected(InetSocketAddress(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

    private fun connected(remote: SocketAddress, local: SocketAddress? = null): Socket =
        createSocket().apply {
            if (local != null) bind(local)
            connect(remote)
        }
}

private class RelayTunnelSocket(
    private val endpoint: RelayEndpoint,
    private val accessToken: String,
) : Socket() {
    @Volatile private var delegate: Socket? = null
    @Volatile private var closed = false
    private var pendingSoTimeout = 0

    @Synchronized
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (closed) throw SocketException("Socket is closed")
        if (delegate != null) throw SocketException("Socket is already connected")
        val connected = RelayProtocol.openTunnel(this.endpoint, accessToken, timeout.coerceAtLeast(1))
        connected.soTimeout = pendingSoTimeout
        delegate = connected
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 5_000)

    override fun bind(bindpoint: SocketAddress?) {
        if (bindpoint != null) throw SocketException("Relay tunnels do not support a local bind override")
    }

    override fun getInputStream(): InputStream = connected().getInputStream()
    override fun getOutputStream(): OutputStream = connected().getOutputStream()

    @Synchronized
    override fun close() {
        closed = true
        delegate?.close()
    }

    override fun shutdownInput() = connected().shutdownInput()
    override fun shutdownOutput() = connected().shutdownOutput()
    override fun isConnected(): Boolean = delegate?.isConnected == true
    override fun isBound(): Boolean = delegate?.isBound == true
    override fun isClosed(): Boolean = closed || delegate?.isClosed == true
    override fun isInputShutdown(): Boolean = delegate?.isInputShutdown == true
    override fun isOutputShutdown(): Boolean = delegate?.isOutputShutdown == true
    override fun getInetAddress(): InetAddress? = delegate?.inetAddress
    override fun getLocalAddress(): InetAddress? = delegate?.localAddress
    override fun getPort(): Int = delegate?.port ?: 0
    override fun getLocalPort(): Int = delegate?.localPort ?: -1
    override fun getRemoteSocketAddress(): SocketAddress? = delegate?.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate?.localSocketAddress
    override fun getChannel(): SocketChannel? = delegate?.channel

    override fun setSoTimeout(timeout: Int) {
        pendingSoTimeout = timeout
        delegate?.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = delegate?.soTimeout ?: pendingSoTimeout
    override fun setTcpNoDelay(on: Boolean) { delegate?.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate?.tcpNoDelay ?: false
    override fun setKeepAlive(on: Boolean) { delegate?.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate?.keepAlive ?: false
    override fun setReuseAddress(on: Boolean) { delegate?.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate?.reuseAddress ?: false
    override fun setReceiveBufferSize(size: Int) { delegate?.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate?.receiveBufferSize ?: 64 * 1024
    override fun setSendBufferSize(size: Int) { delegate?.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate?.sendBufferSize ?: 64 * 1024
    override fun setOOBInline(on: Boolean) { delegate?.oobInline = on }
    override fun getOOBInline(): Boolean = delegate?.oobInline ?: false
    override fun setTrafficClass(tc: Int) { delegate?.trafficClass = tc }
    override fun getTrafficClass(): Int = delegate?.trafficClass ?: 0
    override fun sendUrgentData(data: Int) = connected().sendUrgentData(data)

    private fun connected(): Socket = delegate ?: throw SocketException("Relay tunnel is not connected")
}
