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
        val profile = pairedRelayProfile(bundle(), success())

        assertEquals("relay.example.com:2333", profile.relayEndpoint)
        assertEquals(ConnectionRoute.RELAY, profile.selectedRoute)
        assertEquals("127.0.0.1", profile.lastAddress)
    }

    @Test
    fun qrPairingRejectsChangedHostIdentity() {
        val changed = success().copy(hostId = "0198f142-5a00-7000-8000-000000000099")
        assertThrows(IllegalStateException::class.java) {
            pairedRelayProfile(bundle(), changed)
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
        nativeTransportVersion = 1,
        domainProtocolVersions = listOf(1),
    )

    private companion object {
        const val HOST_ID = "0198f142-5a00-7000-8000-000000000002"
    }
}
