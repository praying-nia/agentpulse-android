package moe.gensoukyo.agentpulse.protocol

import java.security.SecureRandom
import java.util.UUID

object UuidV7 {
    private val random = SecureRandom()

    fun generate(nowMillis: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes[0] = (nowMillis ushr 40).toByte()
        bytes[1] = (nowMillis ushr 32).toByte()
        bytes[2] = (nowMillis ushr 24).toByte()
        bytes[3] = (nowMillis ushr 16).toByte()
        bytes[4] = (nowMillis ushr 8).toByte()
        bytes[5] = nowMillis.toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        var most = 0L
        var least = 0L
        for (index in 0 until 8) most = (most shl 8) or (bytes[index].toLong() and 0xff)
        for (index in 8 until 16) least = (least shl 8) or (bytes[index].toLong() and 0xff)
        return UUID(most, least).toString()
    }

    fun require(value: String, field: String): String {
        val parsed = try {
            UUID.fromString(value)
        } catch (error: IllegalArgumentException) {
            throw ProtocolException("$field must be a UUIDv7", error)
        }
        if (parsed.version() != 7 || parsed.toString() != value.lowercase()) {
            throw ProtocolException("$field must be a canonical UUIDv7")
        }
        return value
    }
}
