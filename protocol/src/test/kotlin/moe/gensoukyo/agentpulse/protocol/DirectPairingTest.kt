package moe.gensoukyo.agentpulse.protocol

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectPairingTest {
    private val fixture = requireNotNull(javaClass.getResource("/pairing-v2/pairing_bundle.json")).readText()
    private fun uri(json: String, version: Int = 2) = "agentpulse://pair/v$version/" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun directFixturePreservesMappedPairingEndpoint() {
        val bundle = PairingCodec.decodeUri(uri(fixture))
        assertEquals("direct", bundle.route)
        assertEquals("", bundle.relayEndpoint)
        assertEquals("public.example.com", bundle.address)
        assertEquals(44321, bundle.port)
        for (host in listOf("203.0.113.10", "2001:db8::1")) {
            assertEquals(host, PairingCodec.decodeUri(uri(fixture.replace("public.example.com", host))).address)
        }
    }

    @Test fun rejectsInvalidDirectBundles() {
        for (json in listOf(
            fixture.replace("\"direct\"", "\"auto\""),
            fixture.replace("public.example.com", "https://public.example.com"),
            fixture.replace("public.example.com", "0.0.0.0"),
            fixture.replace("public.example.com", "999.1.2.3"),
            fixture.replace("44321", "0"),
            fixture.replace("\"route\": \"direct\"", "\"route\": \"direct\", \"relay_endpoint\": \"relay.example.com:443\""),
        )) assertThrows(ProtocolException::class.java) { PairingCodec.decodeUri(uri(json)) }
        assertThrows(ProtocolException::class.java) { PairingCodec.decodeUri(uri(fixture, 1)) }
        assertThrows(ProtocolException::class.java) { PairingCodec.decodeUri(uri(fixture), Long.MAX_VALUE) }
    }
}
