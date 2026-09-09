package moe.gensoukyo.agentpulse

import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.gensoukyo.agentpulse.connection.ConnectionPhase
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-device test: decode a live terminal QR and exercise the actual ViewModel/service. */
@RunWith(AndroidJUnit4::class)
class PairingConnectionTest {
    @Test
    fun liveQrConnectsWithoutCardAuthenticationError() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filename = InstrumentationRegistry.getArguments().getString("agentpulseQrFile")
        assumeTrue("Requires a live QR image and terminal approval", filename != null)
        val file = File(instrumentation.targetContext.filesDir, requireNotNull(filename))
        Log.i("APConnectionE2E", "decoding QR")
        val scanner = BarcodeScanning.getClient()
        val uri = try {
            Tasks.await(scanner.process(InputImage.fromFilePath(instrumentation.targetContext,
                android.net.Uri.fromFile(file))), 10, TimeUnit.SECONDS).single().rawValue!!
        } finally {
            scanner.close()
            file.delete()
        }
        Log.i("APConnectionE2E", "QR decoded; launching activity")
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            var activity: MainActivity? = null
            withTimeout(30_000) {
                while (activity == null) {
                    instrumentation.runOnMainSync {
                        activity = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                    }
                    delay(20)
                }
            }
            lateinit var model: MainViewModel
            Log.i("APConnectionE2E", "activity is foreground")
            instrumentation.runOnMainSync {
                model = ViewModelProvider(requireNotNull(activity))[MainViewModel::class.java]
            }
            if (InstrumentationRegistry.getArguments().getString("agentpulseRePairConnected") == "true") {
                withTimeout(10_000) {
                    while (model.state.value.hosts.isEmpty()) delay(20)
                }
                instrumentation.runOnMainSync { model.connect(model.state.value.hosts.first().hostId) }
                withTimeout(30_000) {
                    while (model.connection.value.connection != ConnectionPhase.CONNECTED) delay(20)
                }
                Log.i("APConnectionE2E", "existing connection is live; re-pairing")
            }
            instrumentation.runOnMainSync {
                scope.launch {
                    model.connection.collect { state ->
                        Log.i("APConnectionE2E", "card=${state.connection} error=${state.error}")
                        state.error?.let(failures::add)
                    }
                }
                scope.launch {
                    model.state.collect { Log.i("APConnectionE2E", "pairing=${it.pairing}") }
                }
                model.pair(uri)
            }
            try {
                withTimeout(115_000) {
                    while (model.state.value.pairing != PairingPhase.SUCCEEDED) {
                        check(model.state.value.pairing != PairingPhase.FAILED) { model.state.value.pairingMessage.orEmpty() }
                        delay(20)
                    }
                    while (model.connection.value.connection != ConnectionPhase.CONNECTED) delay(20)
                }
                delay(5_000)
                assertTrue("Connection card errors: $failures", failures.isEmpty())
                assertTrue(model.connection.value.connection == ConnectionPhase.CONNECTED)
            } finally {
                scope.cancel()
            }
        } finally {
            scope.cancel()
        }
    }
}
