package moe.gensoukyo.agentpulse

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.gensoukyo.agentpulse.data.CredentialVault
import moe.gensoukyo.agentpulse.data.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    @Test
    fun savedHostEndpointMatchesInstrumentationExpectation() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val hostId = arguments.getString("agentpulseHostId")
        val expectedAddress = arguments.getString("agentpulseAddress")
        val expectedPort = arguments.getString("agentpulsePort")?.toIntOrNull()
        assumeTrue(
            "agentpulseHostId, agentpulseAddress, and agentpulsePort are required for this E2E assertion",
            hostId != null && expectedAddress != null && expectedPort != null,
        )

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profile = CredentialVault(context).snapshot().hosts.single { it.hostId == hostId }
        assertEquals(expectedAddress, profile.lastAddress)
        assertEquals(expectedPort, profile.lastPort)
    }

    @Test
    fun credentialsSurviveVaultRecreationWithoutPlaintextStorage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostId = "0198f142-5a00-7000-8000-0000000000aa"
        val token = "instrumentation-secret-token"
        val hostName = "Instrumentation Host"
        val profile = HostProfile(
            hostId = hostId,
            hostName = hostName,
            serverName = "$hostId.agentpulse.local",
            caCertificateDer = "base64-test-ca",
            accessToken = token,
            lastAddress = "192.168.50.4",
            lastPort = 49_320,
        )
        val first = CredentialVault(context)

        first.forget(hostId)
        first.upsert(profile)

        val recreated = CredentialVault(context).snapshot()
        assertEquals(profile, recreated.hosts.single { it.hostId == hostId })
        assertTrue(recreated.clientId.isNotBlank())

        val raw = File(context.filesDir, "datastore/encrypted_credentials.preferences_pb").readBytes().decodeToString()
        assertFalse(raw.contains(token))
        assertFalse(raw.contains(hostName))

        first.forget(hostId)
    }
}
