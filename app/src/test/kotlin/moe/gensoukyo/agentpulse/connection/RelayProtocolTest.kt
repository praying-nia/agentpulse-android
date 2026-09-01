package moe.gensoukyo.agentpulse.connection

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayProtocolTest {
    @Test
    fun canonicalCrossLanguageVectorIsStable() {
        val endpoint = RelayEndpoint.parse("relay.example.com:19191")
        val route = RelayProtocol.derive("fixture-device-token", endpoint)
        assertEquals(
            "aCqsldNQU3q4F4wpLIb_VHzyh51lR6SwzzuK9dno5Mk",
            route.routeId,
        )
        assertEquals(
            "hVBZB_Ak8IDNLGAsJuLi4G_Jhdv1WwnK7YPikP0EGhE",
            Base64.getUrlEncoder().withoutPadding().encodeToString(route.authenticationKey),
        )

        val hello = RelayProtocol.clientHello(
            accessToken = "fixture-device-token",
            endpoint = endpoint,
            connectionId = "018f10a1-1e20-77d2-9d90-80ab2f45a711",
            nonce = ByteArray(32) { it.toByte() },
            expiresAtUnixSeconds = 2_000_000_000,
        )
        val root = Json.parseToJsonElement(hello.decodeToString()).jsonObject
        assertEquals(1, root.getValue("relay_version").jsonPrimitive.content.toInt())
        val message = root.getValue("message").jsonObject
        assertEquals("client_hello", message.getValue("type").jsonPrimitive.content)
        assertEquals(route.routeId, message.getValue("route_id").jsonPrimitive.content)
        assertEquals(
            "5GTj5v2L17ruKW7gkNVZAlwuwo309RR8sYYF2U8FoIk",
            message.getValue("proof").jsonPrimitive.content,
        )
    }

    @Test
    fun endpointIsCanonicalAndRejectsUnsafeForms() {
        assertEquals(
            "ap.nonamenona.top:19191",
            RelayEndpoint.parse("AP.NonameNona.Top:19191").authority,
        )
        listOf(
            "https://ap.nonamenona.top:19191",
            "39.105.18.37:19191",
            "localhost:19191",
            "ap.nonamenona.top",
            "ap.nonamenona.top:0",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { RelayEndpoint.parse(value) }
        }
    }
}
