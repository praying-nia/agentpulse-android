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
        val endpoint = RelayEndpoint.parse("relay.example.com:2333")
        val route = RelayProtocol.derive("fixture-device-token", endpoint)
        assertEquals(
            "zHRMqKXGm1oRWx0q8MCJ5jQggwqVtIcG1TkfyS6Oa9w",
            route.routeId,
        )
        assertEquals(
            "U3lobEe7CoJfP8q2lbSX-2TyS_XHlmM5DJX7RPS601c",
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
            "edqSJq5Swq3tbEBrgcsFnG6wlJL_8K5xBKdr8pDWvhg",
            message.getValue("proof").jsonPrimitive.content,
        )
    }

    @Test
    fun endpointIsCanonicalAndRejectsUnsafeForms() {
        assertEquals(
            "relay.example.com:2333",
            RelayEndpoint.parse("Relay.Example.Com:2333").authority,
        )
        listOf(
            "https://relay.example.com:2333",
            "192.0.2.1:2333",
            "localhost:2333",
            "ap.nonamenona.top",
            "ap.nonamenona.top:0",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { RelayEndpoint.parse(value) }
        }
    }
}
