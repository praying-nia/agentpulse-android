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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.gensoukyo.agentpulse.connection.ConnectionPhase
import moe.gensoukyo.agentpulse.connection.ConnectionService
import moe.gensoukyo.agentpulse.connection.ConnectionSnapshot
import moe.gensoukyo.agentpulse.connection.RelayEndpoint
import moe.gensoukyo.agentpulse.data.ConnectionRoute
import moe.gensoukyo.agentpulse.data.HostProfile
import moe.gensoukyo.agentpulse.pairing.NearbyPairingController
import moe.gensoukyo.agentpulse.pairing.QrScanner
import moe.gensoukyo.agentpulse.protocol.EventImportance
import moe.gensoukyo.agentpulse.protocol.EventRecord
import moe.gensoukyo.agentpulse.protocol.SessionView

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

    private lateinit var nearby: NearbyPairingController
    private val bluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) nearby.start() else transientError = getString(R.string.bluetooth_permission_required)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nearby = NearbyPairingController(
            activity = this,
            onPairingUri = viewModel::pair,
            onError = { transientError = it },
        )
        handleIntent(intent)
        setContent {
            AgentPulseTheme {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(transientError) {
                    transientError?.let { snackbar.showSnackbar(it) }
                    transientError = null
                }
                AgentPulseApp(
                    viewModel = viewModel,
                    snackbar = snackbar,
                    onNearby = ::beginNearby,
                    onScanQr = ::beginQr,
                    onConnect = ::beginConnect,
                    scannerVisible = showScanner,
                    onScannerDismiss = { showScanner = false },
                    onQr = { showScanner = false; viewModel.pair(it) },
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
        intent?.dataString?.takeIf { it.startsWith("agentpulse://pair/v1/") }?.let(viewModel::pair)
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
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (required.isEmpty()) {
            pendingConnect = null
            viewModel.connect(profile.hostId)
        } else connectPermissions.launch(required.toTypedArray())
    }

    private fun beginNearby() {
        if (Build.VERSION.SDK_INT < 31) {
            nearby.start()
            return
        }
        val permissions = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) nearby.start()
        else bluetoothPermissions.launch(permissions.toTypedArray())
    }

    private fun beginQr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showScanner = true
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentPulseApp(
    viewModel: MainViewModel,
    snackbar: SnackbarHostState,
    onNearby: () -> Unit,
    onScanQr: () -> Unit,
    onConnect: (HostProfile) -> Unit,
    scannerVisible: Boolean,
    onScannerDismiss: () -> Unit,
    onQr: (String) -> Unit,
    onScannerError: (String) -> Unit,
) {
    val app by viewModel.state.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val selected = app.selectedSessionId?.let(connection.native.sessions::get)
    BoxWithConstraints {
        val twoPane = maxWidth >= 840.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(selected?.session?.title ?: connection.host?.hostName ?: stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (selected != null && !twoPane) IconButton(onClick = { viewModel.selectSession(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.sessions))
                        }
                    },
                    actions = { AssistChip(onClick = {}, label = { Text(stringResource(R.string.read_only)) }) },
                )
            },
        ) { padding ->
            when {
                connection.host != null && twoPane -> SessionScreen(connection, selected, true, viewModel::selectSession, viewModel::disconnect, onConnect, Modifier.padding(padding))
                selected != null -> SessionDetail(selected, connection, Modifier.padding(padding))
                connection.host != null -> SessionScreen(connection, null, false, viewModel::selectSession, viewModel::disconnect, onConnect, Modifier.padding(padding))
                else -> HostScreen(
                    app.hosts,
                    onNearby,
                    onScanQr,
                    onConnect,
                    viewModel::forget,
                    viewModel::configureRelay,
                    viewModel::selectRoute,
                    Modifier.padding(padding),
                )
            }
        }
    }

    if (app.pairing != PairingPhase.IDLE) PairingDialog(app.pairing, app.pairingMessage, viewModel::clearPairingStatus)
    if (scannerVisible) Dialog(onDismissRequest = onScannerDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.scan_qr), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onScannerDismiss) { Text(stringResource(R.string.cancel)) }
                }
                QrScanner(Modifier.fillMaxSize(), onQr, onScannerError)
            }
        }
    }
}

@Composable
private fun HostScreen(
    hosts: List<HostProfile>,
    onNearby: () -> Unit,
    onScanQr: () -> Unit,
    onConnect: (HostProfile) -> Unit,
    onForget: (String) -> Unit,
    onConfigureRelay: (String, String?) -> Unit,
    onSelectRoute: (String, ConnectionRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    var relayEditor by remember { mutableStateOf<HostProfile?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNearby) { Icon(Icons.AutoMirrored.Filled.BluetoothSearching, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pair_nearby)) }
                Button(onClick = onScanQr) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_qr)) }
            }
        }
        if (hosts.isEmpty()) item {
            Column(Modifier.fillParentMaxSize().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Computer, null, Modifier.height(64.dp).width(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.no_hosts), style = MaterialTheme.typography.titleMedium)
            }
        }
        items(hosts, key = HostProfile::hostId) { host ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(host.hostName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(host.serverName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    host.relayEndpoint?.let {
                        Text(
                            stringResource(R.string.relay_endpoint_value, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = host.selectedRoute == ConnectionRoute.LAN,
                            onClick = { onSelectRoute(host.hostId, ConnectionRoute.LAN) },
                            label = { Text(stringResource(R.string.route_lan)) },
                        )
                        FilterChip(
                            selected = host.selectedRoute == ConnectionRoute.RELAY,
                            enabled = host.relayEndpoint != null,
                            onClick = { onSelectRoute(host.hostId, ConnectionRoute.RELAY) },
                            label = { Text(stringResource(R.string.route_relay)) },
                        )
                    }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConnect(host) }) { Text(stringResource(R.string.connect)) }
                        OutlinedButton(onClick = { relayEditor = host }) { Text(stringResource(R.string.relay_settings)) }
                        TextButton(onClick = { onForget(host.hostId) }) { Text(stringResource(R.string.forget)) }
                    }
                }
            }
        }
    }
    relayEditor?.let { host ->
        RelaySettingsDialog(
            host = host,
            onDismiss = { relayEditor = null },
            onSave = { endpoint ->
                onConfigureRelay(host.hostId, endpoint)
                relayEditor = null
            },
        )
    }
}

@Composable
private fun RelaySettingsDialog(
    host: HostProfile,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var value by remember(host.hostId, host.relayEndpoint) { mutableStateOf(host.relayEndpoint.orEmpty()) }
    var error by remember(host.hostId) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relay_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.relay_explanation))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = { Text(stringResource(R.string.relay_endpoint)) },
                    placeholder = { Text("relay.example.com:19191") },
                    isError = error != null,
                    supportingText = error?.let { message -> ({ Text(message) }) },
                    singleLine = true,
                )
                if (host.relayEndpoint != null) {
                    TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.disable_relay)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { RelayEndpoint.parse(value).authority }
                    .onSuccess(onSave)
                    .onFailure { error = it.message }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SessionScreen(
    connection: ConnectionSnapshot,
    selected: SessionView?,
    twoPane: Boolean,
    onSelect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRetry: (HostProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessions = connection.native.sessions.values.sortedByDescending { it.session.updatedAt }
    Column(modifier.fillMaxSize()) {
        ConnectionBanner(connection, onDisconnect, onRetry)
        if (sessions.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (connection.connection == ConnectionPhase.CONNECTING) CircularProgressIndicator() else Text(stringResource(R.string.no_sessions))
            }
        } else if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                SessionList(sessions, onSelect, Modifier.weight(0.42f).fillMaxHeight())
                VerticalDivider(Modifier.fillMaxHeight())
                if (selected == null) {
                    Column(Modifier.weight(0.58f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(stringResource(R.string.select_session))
                    }
                } else {
                    SessionDetail(selected, connection, Modifier.weight(0.58f).fillMaxHeight())
                }
            }
        } else {
            SessionList(sessions, onSelect, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SessionList(sessions: List<SessionView>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(sessions, key = { it.session.id }) { view -> SessionCard(view) { onSelect(view.session.id) } }
    }
}

@Composable
private fun ConnectionBanner(connection: ConnectionSnapshot, onDisconnect: () -> Unit, onRetry: (HostProfile) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(connectionPhaseLabel(connection.connection), fontWeight = FontWeight.SemiBold)
                connection.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (connection.connection == ConnectionPhase.RETRYING) IconButton(onClick = { connection.host?.let(onRetry) }) { Icon(Icons.Default.Refresh, stringResource(R.string.retry)) }
            IconButton(onClick = onDisconnect) { Icon(Icons.Default.LinkOff, stringResource(R.string.disconnect)) }
        }
    }
}

@Composable
private fun SessionCard(view: SessionView, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(view.session.title ?: view.session.externalId ?: view.session.id, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text("${sessionStateLabel(view.session.state)} · ${sessionConnectionLabel(view.session.connectionState)}", color = MaterialTheme.colorScheme.primary)
            view.session.workspaceName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.cursor, view.cursor.toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionDetail(view: SessionView, connection: ConnectionSnapshot, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    LabelValue(stringResource(R.string.status), "${sessionStateLabel(view.session.state)} · ${sessionConnectionLabel(view.session.connectionState)}")
                    LabelValue(stringResource(R.string.workspace), view.session.workspaceName ?: view.session.workspacePath ?: "—")
                    LabelValue(stringResource(R.string.provider), connection.native.providers[view.session.providerId]?.displayName ?: view.session.providerId)
                }
            }
        }
        itemsIndexed(view.events, key = { _, event -> event.id }) { _, event ->
            val colors = when (event.importance) {
                EventImportance.ERROR -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                EventImportance.WARNING, EventImportance.INTERACTION -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                EventImportance.OUTCOME -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                EventImportance.NORMAL -> CardDefaults.cardColors()
            }
            Card(Modifier.fillMaxWidth(), colors = colors) {
                Column(Modifier.padding(14.dp)) {
                    Text(eventTitle(event), fontWeight = FontWeight.SemiBold)
                    event.detail?.let { Text(it, Modifier.padding(top = 4.dp)) }
                    Text("#${event.sequence} · ${event.occurredAt}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun connectionPhaseLabel(phase: ConnectionPhase): String = stringResource(
    when (phase) {
        ConnectionPhase.DISCONNECTED -> R.string.disconnected
        ConnectionPhase.CONNECTING -> R.string.connecting
        ConnectionPhase.CONNECTED -> R.string.connected
        ConnectionPhase.RETRYING -> R.string.retrying
    },
)

@Composable
private fun sessionStateLabel(state: String): String = stringResource(
    when (state) {
        "initializing" -> R.string.state_initializing
        "idle" -> R.string.state_idle
        "running" -> R.string.state_running
        "waiting_for_interaction" -> R.string.state_waiting
        "completed" -> R.string.state_completed
        "failed" -> R.string.state_failed
        "cancelled" -> R.string.state_cancelled
        else -> R.string.state_unknown
    },
)

@Composable
private fun sessionConnectionLabel(state: String): String = stringResource(
    when (state) {
        "connected" -> R.string.connected
        "reconnecting" -> R.string.retrying
        else -> R.string.disconnected
    },
)

@Composable
private fun eventTitle(event: EventRecord): String = stringResource(
    when (event.type) {
        "session_started" -> R.string.event_session_started
        "state_changed" -> R.string.event_state_changed
        "connection_changed" -> R.string.event_connection_changed
        "message" -> R.string.event_message
        "tool_activity" -> R.string.event_tool_activity
        "plan_updated" -> R.string.event_plan_updated
        "progress_updated" -> R.string.event_progress_updated
        "interaction_requested" -> R.string.event_interaction_requested
        "interaction_responded" -> R.string.event_interaction_responded
        "command_issued" -> R.string.event_command_issued
        else -> R.string.event_session_ended
    },
)

@Composable
private fun LabelValue(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(value, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PairingDialog(phase: PairingPhase, message: String?, onDismiss: () -> Unit) {
    val terminal = phase == PairingPhase.SUCCEEDED || phase == PairingPhase.FAILED
    AlertDialog(
        onDismissRequest = { if (terminal) onDismiss() },
        title = { Text(stringResource(if (phase == PairingPhase.WAITING_FOR_HOST) R.string.waiting_for_host else R.string.pairing)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!terminal) CircularProgressIndicator()
                message?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
                if (phase == PairingPhase.SUCCEEDED) Text(stringResource(R.string.paired_successfully))
            }
        },
        confirmButton = { if (terminal) TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
private fun AgentPulseTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
