package moe.gensoukyo.agentpulse

import android.util.Log
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.gensoukyo.agentpulse.connection.ConnectionPhase
import moe.gensoukyo.agentpulse.data.ConnectionRoute
import moe.gensoukyo.agentpulse.connection.CommandSubmissionPhase
import moe.gensoukyo.agentpulse.protocol.AgentCommandPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only runs for an explicitly named, already paired public test Host. */
@RunWith(AndroidJUnit4::class)
class DirectConnectionTest {
    @Test(timeout = 180_000)
    fun savedHostConnectsAndReconnectsOverCellular() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val hostId = args.getString("agentpulseHostId")
        assumeTrue("Requires a paired public Host", hostId != null)
        Log.i("APDirectE2E", "checking cellular network")
        assertCellularWithoutVpn()
        Log.i("APDirectE2E", "waiting for foreground Activity")
        run {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var found: MainViewModel? = null
            withTimeout(30_000) {
                while (found == null) {
                    instrumentation.runOnMainSync {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().firstOrNull()?.let {
                                found = ViewModelProvider(it)[MainViewModel::class.java]
                            }
                    }
                    delay(50)
                }
            }
            val model = requireNotNull(found)
            Log.i("APDirectE2E", "Activity is foreground")
            val profile = withTimeout(10_000) {
                while (model.state.value.hosts.none { it.hostId == hostId }) delay(50)
                model.state.value.hosts.single { it.hostId == hostId }
            }
            assertEquals(ConnectionRoute.DIRECT, profile.selectedRoute)
            assertEquals(null, profile.relayEndpoint)
            args.getString("agentpulseExpectedAddress")?.let { assertEquals(it, profile.directAddress) }
            args.getString("agentpulseExpectedPort")?.let { assertEquals(it.toInt(), profile.directPort) }
            repeat(2) { attempt ->
                Log.i("APDirectE2E", "connecting attempt=$attempt")
                instrumentation.runOnMainSync { model.connect(requireNotNull(hostId)) }
                withTimeout(30_000) {
                    while (model.connection.value.connection != ConnectionPhase.CONNECTED) {
                        check(model.connection.value.error == null) { model.connection.value.error.orEmpty() }
                        delay(50)
                    }
                }
                Log.i("APDirectE2E", "connected attempt=$attempt")
                delay(5_000)
                assertEquals(ConnectionPhase.CONNECTED, model.connection.value.connection)
                assertEquals(null, model.connection.value.error)
                assertEquals(profile.directAddress, model.connection.value.host?.directAddress)
                assertCellularWithoutVpn()
                if (attempt == 0) args.getString("agentpulseSessionId")?.let { sessionId ->
                    withTimeout(20_000) {
                        while (model.connection.value.native.sessions[sessionId] == null) delay(50)
                    }
                    val before = model.connection.value.native.sessions.getValue(sessionId).cursor
                    Log.i("APDirectE2E", "message baseline cursor=$before")
                    var commandId: String? = null
                    instrumentation.runOnMainSync {
                        commandId = model.submitCommand(sessionId, AgentCommandPayload.SubmitPrompt(
                            "这是公网直连验收。请只回复 AP_DIRECT_PUBLIC_OK，不使用工具，不修改文件。",
                        ))
                    }
                    val id = requireNotNull(commandId)
                    withTimeout(20_000) {
                        while (model.connection.value.commandSubmissions[id]?.phase != CommandSubmissionPhase.ACCEPTED) {
                            check(model.connection.value.commandSubmissions[id]?.phase != CommandSubmissionPhase.FAILED)
                            delay(50)
                        }
                    }
                    Log.i("APDirectE2E", "phone message accepted by Host")
                    var lastCursor = before
                    withTimeout(120_000) {
                        while (model.connection.value.native.sessions[sessionId]?.events?.none {
                            it.sequence > before && it.messageRole == "assistant" && it.detail.orEmpty().contains("AP_DIRECT_PUBLIC_OK")
                        } != false) {
                            val view = model.connection.value.native.sessions[sessionId]
                            if (view != null && view.cursor != lastCursor) {
                                lastCursor = view.cursor
                                Log.i("APDirectE2E", "received cursor=$lastCursor events=" + view.events.takeLast(12).joinToString {
                                    "${it.sequence}:${it.type}:${it.messageRole}:marker=${it.detail.orEmpty().contains("AP_DIRECT_PUBLIC_OK")}" })
                            }
                            check(model.connection.value.error == null) { model.connection.value.error.orEmpty() }
                            delay(100)
                        }
                    }
                    Log.i("APDirectE2E", "assistant reply received over public direct connection")
                }
                Log.i("APDirectE2E", "disconnecting attempt=$attempt")
                instrumentation.runOnMainSync { model.disconnect() }
                withTimeout(10_000) {
                    while (model.connection.value.connection != ConnectionPhase.DISCONNECTED) delay(50)
                }
                Log.i("APDirectE2E", "disconnected attempt=$attempt")
            }
        }
    }
}

internal fun assertCellularWithoutVpn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val capabilities = requireNotNull(manager.getNetworkCapabilities(manager.activeNetwork))
    assertTrue("Acceptance requires mobile data", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    assertTrue("Acceptance must bypass VPN", !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    assertTrue("Acceptance must bypass Wi-Fi", !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
}
