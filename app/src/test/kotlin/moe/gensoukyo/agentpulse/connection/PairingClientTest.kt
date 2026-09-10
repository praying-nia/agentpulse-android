package moe.gensoukyo.agentpulse.connection

import moe.gensoukyo.agentpulse.data.ConnectionRoute
import moe.gensoukyo.agentpulse.protocol.PairingBundle
import moe.gensoukyo.agentpulse.protocol.PairingServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingClientTest {
    @Test
    fun qrPairingAlwaysCreatesSelectedRelayProfile() {
        val profile = pairedHostProfile(bundle(), success())

        assertEquals("relay.example.com:2333", profile.relayEndpoint)
        assertEquals(ConnectionRoute.RELAY, profile.selectedRoute)
        assertEquals("127.0.0.1", profile.lastAddress)
    }

    @Test
    fun directPairingStoresPublicNativeEndpointAndRouteAcrossSerialization() {
        val profile = pairedHostProfile(
            bundle().copy(route = "direct", relayEndpoint = "", address = "public.example.com", port = 44321),
            success().copy(nativeAddress = "public.example.com", nativePort = 44320),
        )
        assertEquals(null, profile.relayEndpoint)
        assertEquals(ConnectionRoute.DIRECT, profile.selectedRoute)
        assertEquals("public.example.com", profile.directAddress)
        assertEquals(44320, profile.directPort)
        val json = kotlinx.serialization.json.Json
        val encoded = json.encodeToString(moe.gensoukyo.agentpulse.data.HostProfile.serializer(), profile)
        assertEquals(profile, json.decodeFromString(moe.gensoukyo.agentpulse.data.HostProfile.serializer(), encoded))
        val legacy = """{"hostId":"$HOST_ID","hostName":"Host","serverName":"host.agentpulse.local","caCertificateDer":"ca","accessToken":"token","lastAddress":"192.168.1.2","lastPort":49320,"selectedRoute":"LAN"}"""
        val old = json.decodeFromString(moe.gensoukyo.agentpulse.data.HostProfile.serializer(), legacy)
        assertEquals(ConnectionRoute.LAN, old.selectedRoute)
        assertEquals(null, old.directAddress)
    }

    @Test
    fun qrPairingRejectsChangedHostIdentity() {
        val changed = success().copy(hostId = "0198f142-5a00-7000-8000-000000000099")
        assertThrows(IllegalStateException::class.java) {
            pairedHostProfile(bundle(), changed)
        }
    }

    private fun bundle() = PairingBundle(
        pairingId = "0198f142-5a00-7000-8000-000000000001",
        hostId = HOST_ID,
        hostName = "Studio Host",
        serverName = "$HOST_ID.agentpulse.local",
        address = "127.0.0.1",
        port = 49_321,
        leafSha256 = "ab".repeat(32),
        bootstrapToken = "bootstrap-secret",
        relayEndpoint = "relay.example.com:2333",
        expiresAtUnixSeconds = 4_102_444_800,
    )

    private fun success() = PairingServerMessage.Succeeded(
        hostId = HOST_ID,
        hostName = "Studio Host",
        caCertificateDer = "base64-ca",
        serverName = "$HOST_ID.agentpulse.local",
        nativeAddress = "127.0.0.1",
        nativePort = 49_320,
        accessToken = "device-secret",
        nativeTransportVersion = 3,
        domainProtocolVersions = listOf(2),
    )

    private companion object {
        const val HOST_ID = "0198f142-5a00-7000-8000-000000000002"
    }
}
