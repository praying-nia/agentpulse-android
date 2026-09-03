package moe.gensoukyo.agentpulse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.gensoukyo.agentpulse.connection.ConnectionService
import moe.gensoukyo.agentpulse.data.ConnectionRoute
import moe.gensoukyo.agentpulse.data.HostProfile
import moe.gensoukyo.agentpulse.ui.AgentPulseApp
import moe.gensoukyo.agentpulse.ui.theme.AgentPulseTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private var pendingConnect: HostProfile? = null
    private var showScanner by mutableStateOf(false)
    private var transientError by mutableStateOf<String?>(null)

    private val connectPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val localAllowed = Build.VERSION.SDK_INT < 37 || grants[Manifest.permission.ACCESS_LOCAL_NETWORK] != false
        if (localAllowed) pendingConnect?.hostId?.let(viewModel::connect)
        else transientError = getString(R.string.local_network_permission_required)
        pendingConnect = null
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true else transientError = getString(R.string.camera_permission_required)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val preferences by viewModel.uiPreferences.collectAsStateWithLifecycle()
            AgentPulseTheme(preferences) {
                AgentPulseApp(
                    viewModel = viewModel,
                    onScanQr = ::beginQr,
                    onConnect = ::beginConnect,
                    scannerVisible = showScanner,
                    onScannerDismiss = { showScanner = false },
                    onQr = { showScanner = false; viewModel.pair(it) },
                    transientError = transientError,
                    onTransientErrorShown = { transientError = null },
                    onScannerError = { transientError = it },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(ConnectionService.EXTRA_SESSION_ID)?.let(viewModel::selectSession)
    }

    private fun beginConnect(profile: HostProfile) {
        pendingConnect = profile
        val required = buildList {
            if (
                profile.selectedRoute == ConnectionRoute.LAN &&
                Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            if (
                Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (required.isEmpty()) {
            pendingConnect = null
            viewModel.connect(profile.hostId)
        } else {
            connectPermissions.launch(required.toTypedArray())
        }
    }

    private fun beginQr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
}
