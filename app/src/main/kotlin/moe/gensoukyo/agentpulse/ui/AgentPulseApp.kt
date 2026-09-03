package moe.gensoukyo.agentpulse.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.launch
import moe.gensoukyo.agentpulse.BuildConfig
import moe.gensoukyo.agentpulse.MainViewModel
import moe.gensoukyo.agentpulse.PairingPhase
import moe.gensoukyo.agentpulse.R
import moe.gensoukyo.agentpulse.connection.ConnectionPhase
import moe.gensoukyo.agentpulse.connection.ConnectionSnapshot
import moe.gensoukyo.agentpulse.connection.RelayEndpoint
import moe.gensoukyo.agentpulse.data.ColorSource
import moe.gensoukyo.agentpulse.data.ConnectionRoute
import moe.gensoukyo.agentpulse.data.HostProfile
import moe.gensoukyo.agentpulse.data.ThemeMode
import moe.gensoukyo.agentpulse.data.UiPreferences
import moe.gensoukyo.agentpulse.data.formatHexColor
import moe.gensoukyo.agentpulse.data.parseHexColor
import moe.gensoukyo.agentpulse.pairing.QrScanner
import moe.gensoukyo.agentpulse.protocol.ApprovalPrompt
import moe.gensoukyo.agentpulse.protocol.AgentCommandPayload
import moe.gensoukyo.agentpulse.protocol.ApprovalSubject
import moe.gensoukyo.agentpulse.protocol.ApprovalSubmissionState
import moe.gensoukyo.agentpulse.protocol.EventImportance
import moe.gensoukyo.agentpulse.protocol.EventRecord
import moe.gensoukyo.agentpulse.protocol.SessionView
import moe.gensoukyo.agentpulse.protocol.FormAnswer
import moe.gensoukyo.agentpulse.protocol.FormPrompt
import moe.gensoukyo.agentpulse.ui.theme.semanticColors

private enum class Destination(val label: Int) {
    CONNECTIONS(R.string.connections),
    SESSIONS(R.string.sessions),
    SETTINGS(R.string.settings),
}

internal enum class SessionFilter { ALL, RUNNING, WAITING, FINISHED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPulseApp(
    viewModel: MainViewModel,
    onScanQr: () -> Unit,
    onConnect: (HostProfile) -> Unit,
    scannerVisible: Boolean,
    onScannerDismiss: () -> Unit,
    onQr: (String) -> Unit,
    transientError: String?,
    onTransientErrorShown: () -> Unit,
    onScannerError: (String) -> Unit,
) {
    val app by viewModel.state.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val preferences by viewModel.uiPreferences.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var destinationName by rememberSaveable { mutableStateOf(Destination.CONNECTIONS.name) }
    var settingsHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = Destination.valueOf(destinationName)
    val selected = app.selectedSessionId?.let(connection.native.sessions::get)

    LaunchedEffect(transientError) {
        transientError?.let { snackbar.showSnackbar(it) }
        if (transientError != null) onTransientErrorShown()
    }
    LaunchedEffect(app.selectedSessionId) {
        if (app.selectedSessionId != null) destinationName = Destination.SESSIONS.name
    }

    BoxWithConstraints {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                AppTopBar(
                    destination = destination,
                    selected = selected,
                    expanded = expanded,
                    onBack = { viewModel.selectSession(null) },
                    onScanQr = onScanQr,
                )
            },
            bottomBar = {
                if (!expanded) {
                    DestinationBar(destination) { next ->
                        destinationName = next.name
                        if (next != Destination.SESSIONS || destination == Destination.SESSIONS) viewModel.selectSession(null)
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (expanded) {
                    DestinationRail(destination) { next ->
                        destinationName = next.name
                        if (next != Destination.SESSIONS) viewModel.selectSession(null)
                    }
                }
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = { destinationTransition(initialState, targetState) },
                    contentKey = Destination::name,
                    label = "destination",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { visibleDestination ->
                    when (visibleDestination) {
                        Destination.CONNECTIONS -> ConnectionsScreen(
                            hosts = app.hosts,
                            connection = connection,
                            onScanQr = onScanQr,
                            onConnect = onConnect,
                            onDisconnect = viewModel::disconnect,
                            onSelectRoute = viewModel::selectRoute,
                            onOpenSessions = { sessionId ->
                                destinationName = Destination.SESSIONS.name
                                viewModel.selectSession(sessionId)
                            },
                            onOpenSettings = { hostId ->
                                settingsHostId = hostId
                                destinationName = Destination.SETTINGS.name
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Destination.SESSIONS -> SessionsScreen(
                            connection = connection,
                            selected = selected,
                            expanded = expanded,
                            onSelect = viewModel::selectSession,
                            onSubmitApproval = viewModel::submitApproval,
                            onSubmitForm = viewModel::submitForm,
                            onSubmitCommand = viewModel::submitCommand,
                            onCommandConsumed = viewModel::consumeCommand,
                            onRetry = onConnect,
                            onDisconnect = viewModel::disconnect,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Destination.SETTINGS -> SettingsScreen(
                            hosts = app.hosts,
                            targetHostId = settingsHostId,
                            connection = connection,
                            preferences = preferences,
                            onThemeMode = viewModel::setThemeMode,
                            onColorSource = viewModel::setColorSource,
                            onCustomSeed = viewModel::setCustomSeed,
                            onConfigureRelay = viewModel::configureRelay,
                            onSelectRoute = viewModel::selectRoute,
                            onForget = viewModel::forget,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    if (app.pairing != PairingPhase.IDLE) {
        PairingDialog(app.pairing, app.pairingMessage, viewModel::clearPairingStatus)
    }
    if (scannerVisible) {
        ScannerDialog(onScannerDismiss, onQr, onScannerError)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    destination: Destination,
    selected: SessionView?,
    expanded: Boolean,
    onBack: () -> Unit,
    onScanQr: () -> Unit,
) {
    val detailVisible = selected != null && destination == Destination.SESSIONS && !expanded
    TopAppBar(
        title = {
            Text(
                if (detailVisible) selected.displayTitle()
                else if (destination == Destination.CONNECTIONS) stringResource(R.string.app_name)
                else stringResource(destination.label),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (detailVisible) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_sessions))
                }
            }
        },
        actions = {
            if (destination == Destination.CONNECTIONS) {
                IconButton(onClick = onScanQr) {
                    Icon(Icons.Default.QrCodeScanner, stringResource(R.string.scan_qr))
                }
            }
        },
    )
}

@Composable
private fun DestinationBar(selected: Destination, onSelect: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { DestinationIcon(destination) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}

@Composable
private fun DestinationRail(selected: Destination, onSelect: (Destination) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        Spacer(Modifier.height(12.dp))
        Destination.entries.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { DestinationIcon(destination) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}

@Composable
private fun DestinationIcon(destination: Destination) {
    Icon(
        when (destination) {
            Destination.CONNECTIONS -> Icons.Outlined.Hub
            Destination.SESSIONS -> Icons.Outlined.ChatBubbleOutline
            Destination.SETTINGS -> Icons.Default.Settings
        },
        contentDescription = null,
    )
}

@Composable
private fun ConnectionsScreen(
    hosts: List<HostProfile>,
    connection: ConnectionSnapshot,
    onScanQr: () -> Unit,
    onConnect: (HostProfile) -> Unit,
    onDisconnect: () -> Unit,
    onSelectRoute: (String, ConnectionRoute) -> Unit,
    onOpenSessions: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val orderedHosts = hosts.sortedWith(
        compareByDescending<HostProfile> { it.hostId == connection.host?.hostId }
            .thenBy { it.hostName.lowercase(Locale.getDefault()) },
    )
    val sessions = sessionsNewestFirst(connection.native.sessions.values).take(3)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Icon(Icons.Default.QrCodeScanner, null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.scan_qr))
            }
        }
        if (orderedHosts.isEmpty()) {
            item { EmptyConnections(onScanQr) }
        } else {
            item { SectionTitle(stringResource(R.string.my_connections)) }
            items(orderedHosts, key = HostProfile::hostId) { host ->
                HostCard(
                    host = host,
                    connection = connection,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onSelectRoute = onSelectRoute,
                    onSettings = { onOpenSettings(host.hostId) },
                )
            }
        }
        if (sessions.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.recent_sessions)) }
            items(sessions, key = { it.session.id }) { view ->
                CompactSessionCard(view) { onOpenSessions(view.session.id) }
            }
        }
    }
}

@Composable
private fun EmptyConnections(onScanQr: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Outlined.Devices,
                    null,
                    Modifier.padding(16.dp).size(42.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(stringResource(R.string.no_hosts), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.no_hosts_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onScanQr) { Text(stringResource(R.string.start_scanning)) }
        }
    }
}

@Composable
private fun HostCard(
    host: HostProfile,
    connection: ConnectionSnapshot,
    onConnect: (HostProfile) -> Unit,
    onDisconnect: () -> Unit,
    onSelectRoute: (String, ConnectionRoute) -> Unit,
    onSettings: () -> Unit,
) {
    val active = connection.host?.hostId == host.hostId
    val phase = if (active) connection.connection else ConnectionPhase.DISCONNECTED
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(phase)
                Spacer(Modifier.width(8.dp))
                Text(host.hostName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StatusPill(connectionPhaseLabel(phase), phase)
            }
            Text(host.serverName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            host.relayEndpoint?.let {
                Text(stringResource(R.string.relay_endpoint_value, it), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (active && phase != ConnectionPhase.DISCONNECTED) {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.LinkOff, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.disconnect))
                    }
                } else {
                    Button(onClick = { onConnect(host) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Cloud, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.connect))
                    }
                }
                OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Settings, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings))
                }
            }
            if (active) connection.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CompactSessionCard(view: SessionView, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(view.displayTitle(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    view.events.lastOrNull()?.detail ?: sessionStateLabel(view.session.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(formatTimestamp(view.session.updatedAt), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SessionsScreen(
    connection: ConnectionSnapshot,
    selected: SessionView?,
    expanded: Boolean,
    onSelect: (String?) -> Unit,
    onSubmitApproval: (String, String, String) -> Unit,
    onSubmitForm: (String, String, Map<String, FormAnswer>) -> Unit,
    onSubmitCommand: (String, AgentCommandPayload) -> String?,
    onCommandConsumed: (String) -> Unit,
    onRetry: (HostProfile) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!expanded) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = { sessionTransition(initialState, targetState) },
            contentKey = { it?.session?.id ?: "session-list" },
            label = "session-detail",
            modifier = modifier.fillMaxSize(),
        ) { visibleSession ->
            if (visibleSession == null) {
                SessionBrowser(
                    connection = connection,
                    onSelect = { onSelect(it) },
                    onRetry = onRetry,
                    onDisconnect = onDisconnect,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SessionDetail(
                    visibleSession,
                    connection,
                    onSubmitApproval,
                    onSubmitForm,
                    onSubmitCommand,
                    onCommandConsumed,
                    Modifier.fillMaxSize(),
                )
            }
        }
        return
    }
    Row(modifier.fillMaxSize()) {
        SessionBrowser(
            connection = connection,
            onSelect = { onSelect(it) },
            onRetry = onRetry,
            onDisconnect = onDisconnect,
            modifier = if (expanded) Modifier.weight(0.43f) else Modifier.fillMaxSize(),
        )
        if (expanded) {
            VerticalDivider(Modifier.fillMaxHeight())
            if (selected == null) {
                EmptySessionSelection(Modifier.weight(0.57f))
            } else {
                SessionDetail(
                    selected,
                    connection,
                    onSubmitApproval,
                    onSubmitForm,
                    onSubmitCommand,
                    onCommandConsumed,
                    Modifier.weight(0.57f),
                )
            }
        }
    }
}

private fun destinationTransition(initial: Destination, target: Destination): ContentTransform {
    val forward = target.ordinal > initial.ordinal
    return horizontalPageTransition(forward, distanceDivisor = 5)
}

private fun sessionTransition(initial: SessionView?, target: SessionView?): ContentTransform =
    horizontalPageTransition(forward = initial == null && target != null, distanceDivisor = 3)

private fun horizontalPageTransition(forward: Boolean, distanceDivisor: Int): ContentTransform {
    val enterDirection = if (forward) 1 else -1
    val exitDirection = -enterDirection
    val slide = tween<IntOffset>(durationMillis = 280, easing = FastOutSlowInEasing)
    val fade = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)
    return (
        slideInHorizontally(animationSpec = slide) { width -> enterDirection * width / distanceDivisor } +
            fadeIn(animationSpec = fade)
        ).togetherWith(
        slideOutHorizontally(animationSpec = slide) { width -> exitDirection * width / distanceDivisor } +
            fadeOut(animationSpec = fade),
    )
}

@Composable
private fun SessionBrowser(
    connection: ConnectionSnapshot,
    onSelect: (String) -> Unit,
    onRetry: (HostProfile) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(SessionFilter.ALL.name) }
    val filter = SessionFilter.valueOf(filterName)
    val sessions = filterSessions(connection.native.sessions.values, query, filter)
    Column(modifier) {
        ConnectionStrip(connection, onDisconnect, onRetry)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text(stringResource(R.string.search_sessions)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SessionFilter.entries.forEach { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = { filterName = option.name },
                    label = { Text(sessionFilterLabel(option)) },
                )
            }
        }
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (connection.native.sessions.isEmpty()) stringResource(R.string.no_sessions)
                    else stringResource(R.string.no_matching_sessions),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.session.id }) { view -> SessionCard(view) { onSelect(view.session.id) } }
            }
        }
    }
}

@Composable
private fun ConnectionStrip(connection: ConnectionSnapshot, onDisconnect: () -> Unit, onRetry: (HostProfile) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(connection.connection)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(connection.host?.hostName ?: stringResource(R.string.no_active_connection), fontWeight = FontWeight.SemiBold)
                Text(connectionPhaseLabel(connection.connection), style = MaterialTheme.typography.labelSmall)
            }
            if (connection.connection == ConnectionPhase.RETRYING) {
                IconButton(onClick = { connection.host?.let(onRetry) }) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.retry))
                }
            }
            if (connection.host != null) {
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Default.LinkOff, stringResource(R.string.disconnect))
                }
            }
        }
    }
}

@Composable
private fun SessionCard(view: SessionView, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(view.displayTitle(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                SessionStatePill(view.session.state)
            }
            view.events.lastOrNull()?.detail?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                Text(view.session.workspaceName ?: view.session.workspacePath ?: "—", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text(formatTimestamp(view.session.updatedAt), style = MaterialTheme.typography.labelSmall)
            }
            val pendingCount = view.pendingApprovals.size + view.pendingForms.size
            if (pendingCount > 0) {
                Text(
                    pluralStringResource(R.plurals.pending_approvals, pendingCount, pendingCount),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun EmptySessionSelection(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.select_session), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionDetail(
    view: SessionView,
    connection: ConnectionSnapshot,
    onSubmitApproval: (String, String, String) -> Unit,
    onSubmitForm: (String, String, Map<String, FormAnswer>) -> Unit,
    onSubmitCommand: (String, AgentCommandPayload) -> String?,
    onCommandConsumed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = eventsNewestFirst(view.events)
    val approvals = view.pendingApprovals.values.sortedByDescending(ApprovalPrompt::requestedAt)
    val forms = view.pendingForms.values.sortedByDescending(FormPrompt::requestedAt)
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SessionSummary(view, connection) }
            if (approvals.isNotEmpty() || forms.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.action_required)) }
                items(forms, key = FormPrompt::id) { form ->
                    FormCard(form) { answers -> onSubmitForm(view.session.id, form.id, answers) }
                }
                items(approvals, key = ApprovalPrompt::id) { approval ->
                    ApprovalCard(approval) { optionId -> onSubmitApproval(view.session.id, approval.id, optionId) }
                }
                item { SectionTitle(stringResource(R.string.latest_events)) }
            }
            itemsIndexed(events, key = { _, event -> event.id }) { _, event -> EventCard(event) }
            if (events.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_events),
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        CommandComposer(
            enabled = connection.native.phase == moe.gensoukyo.agentpulse.protocol.NativeState.Phase.LIVE,
            workspace = view.session.workspacePath,
            connectionError = connection.error,
            submissions = connection.commandSubmissions,
            onCommandConsumed = onCommandConsumed,
        ) { command -> onSubmitCommand(view.session.id, command) }
    }
}

@Composable
private fun SessionSummary(view: SessionView, connection: ConnectionSnapshot) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(view.displayTitle(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SessionStatePill(view.session.state)
            }
            LabelValue(stringResource(R.string.workspace), view.session.workspaceName ?: view.session.workspacePath ?: "—")
            LabelValue(stringResource(R.string.provider), connection.native.providers[view.session.providerId]?.displayName ?: view.session.providerId)
            LabelValue(stringResource(R.string.updated), formatTimestamp(view.session.updatedAt))
        }
    }
}

@Composable
private fun EventCard(event: EventRecord) {
    val semantic = MaterialTheme.semanticColors
    val (icon, tint, container) = when (event.importance) {
        EventImportance.ERROR -> Triple(Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
        EventImportance.WARNING -> Triple(Icons.Default.WarningAmber, semantic.warning, semantic.warningContainer)
        EventImportance.INTERACTION -> Triple(Icons.Outlined.ChatBubbleOutline, semantic.info, semantic.infoContainer)
        EventImportance.OUTCOME -> Triple(Icons.Default.CheckCircle, semantic.success, semantic.successContainer)
        EventImportance.NORMAL -> Triple(Icons.Outlined.ChatBubbleOutline, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(eventTitle(event), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(formatTimestamp(event.occurredAt), style = MaterialTheme.typography.labelSmall)
                }
                event.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text("#${event.sequence}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CommandComposer(
    enabled: Boolean,
    workspace: String?,
    connectionError: String?,
    submissions: Map<String, moe.gensoukyo.agentpulse.connection.CommandSubmission>,
    onCommandConsumed: (String) -> Unit,
    onSubmit: (AgentCommandPayload) -> String?,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var pendingCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var submissionError by rememberSaveable { mutableStateOf<String?>(null) }
    val submission = pendingCommandId?.let(submissions::get)
    val unavailable = stringResource(R.string.connection_unavailable)
    val invalid = stringResource(R.string.command_invalid)
    LaunchedEffect(pendingCommandId, submission?.phase) {
        val commandId = pendingCommandId ?: return@LaunchedEffect
        when (submission?.phase) {
            moe.gensoukyo.agentpulse.connection.CommandSubmissionPhase.ACCEPTED -> {
                text = ""
                pendingCommandId = null
                submissionError = null
                onCommandConsumed(commandId)
            }
            moe.gensoukyo.agentpulse.connection.CommandSubmissionPhase.FAILED -> {
                pendingCommandId = null
                submissionError = submission.error ?: unavailable
                onCommandConsumed(commandId)
            }
            else -> Unit
        }
    }
    fun submit(payload: AgentCommandPayload) {
        val commandId = onSubmit(payload)
        if (commandId == null) {
            submissionError = connectionError ?: unavailable
        } else {
            pendingCommandId = commandId
            submissionError = null
        }
    }
    val suggestions = if (text.startsWith("/") && !text.contains(' ')) {
        COMMON_COMMANDS.filter { it.startsWith(text) }
    } else emptyList()
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (suggestions.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { command ->
                        AssistChip(onClick = { text = "$command " }, label = { Text(command) })
                    }
                }
            }
            if (text.trim() == "/plan") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(false, { submit(AgentCommandPayload.SetPlanMode(true)) }, label = { Text(stringResource(R.string.plan_on)) })
                    FilterChip(false, { submit(AgentCommandPayload.SetPlanMode(false)) }, label = { Text(stringResource(R.string.plan_off)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        if (it.encodeToByteArray().size <= 64 * 1024) {
                            text = it
                            submissionError = null
                        }
                    },
                    enabled = enabled && pendingCommandId == null,
                    placeholder = { Text(stringResource(R.string.message_placeholder)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    isError = submissionError != null,
                    supportingText = when {
                        pendingCommandId != null -> ({ Text(stringResource(R.string.command_sending)) })
                        submissionError != null -> ({ Text(requireNotNull(submissionError)) })
                        !enabled -> ({ Text(connectionError ?: stringResource(R.string.command_waiting_for_connection)) })
                        else -> null
                    },
                )
                if (pendingCommandId != null) {
                    CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                } else {
                    IconButton(
                        enabled = enabled && text.isNotBlank(),
                        onClick = {
                            val command = parseComposerCommand(text, workspace)
                            if (command == null) submissionError = invalid else submit(command)
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                    }
                }
            }
        }
    }
}

private val COMMON_COMMANDS = listOf(
    "/model", "/resume", "/clear", "/plan", "/compact", "/review", "/rename", "/fork", "/status", "/permissions", "/stop", "/queue",
)

internal fun parseComposerCommand(value: String, workspace: String?): AgentCommandPayload? {
    val text = value.trim()
    if (text.isEmpty()) return null
    if (!text.startsWith('/')) return AgentCommandPayload.SubmitPrompt(text)
    val command = text.substringBefore(' ').lowercase(Locale.ROOT)
    val argument = text.substringAfter(' ', "").trim().ifEmpty { null }
    return when (command) {
        "/model" -> argument?.split(Regex("\\s+"), limit = 2)?.let { AgentCommandPayload.SelectModel(it[0], it.getOrNull(1)) } ?: AgentCommandPayload.ListModels
        "/resume" -> when {
            argument == null -> AgentCommandPayload.ListThreads()
            argument.startsWith("--cursor ") -> AgentCommandPayload.ListThreads(argument.removePrefix("--cursor ").trim())
            else -> AgentCommandPayload.ResumeThread(argument)
        }
        "/clear", "/new" -> workspace?.let(AgentCommandPayload::StartThread)
        "/plan" -> AgentCommandPayload.SetPlanMode(argument?.lowercase(Locale.ROOT) != "off")
        "/compact" -> AgentCommandPayload.Compact
        "/review" -> AgentCommandPayload.Review(argument)
        "/rename" -> argument?.let(AgentCommandPayload::Rename)
        "/fork" -> AgentCommandPayload.Fork
        "/status" -> AgentCommandPayload.Status
        "/permissions" -> argument?.let(AgentCommandPayload::SelectPermissionProfile) ?: AgentCommandPayload.ListPermissionProfiles
        "/stop" -> AgentCommandPayload.Cancel
        "/queue" -> argument?.lowercase(Locale.ROOT)?.takeIf { it in setOf("pause", "resume", "clear") }?.let(AgentCommandPayload::Queue)
        "/steer" -> argument?.let { AgentCommandPayload.SubmitPrompt(it, steer = true) }
        else -> null
    }
}

@Composable
private fun SettingsScreen(
    hosts: List<HostProfile>,
    targetHostId: String?,
    connection: ConnectionSnapshot,
    preferences: UiPreferences,
    onThemeMode: (ThemeMode) -> Unit,
    onColorSource: (ColorSource) -> Unit,
    onCustomSeed: (Int) -> Unit,
    onConfigureRelay: (String, String?) -> Unit,
    onSelectRoute: (String, ConnectionRoute) -> Unit,
    onForget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val host = hosts.firstOrNull { it.hostId == targetHostId } ?: connection.host ?: hosts.firstOrNull()
    var relayEditor by remember { mutableStateOf<HostProfile?>(null) }
    var customEditor by remember { mutableStateOf(false) }
    var forgetTarget by remember { mutableStateOf<HostProfile?>(null) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionTitle(stringResource(R.string.appearance)) }
        item {
            SettingsCard {
                SettingHeading(Icons.Default.DarkMode, stringResource(R.string.theme_mode))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = preferences.themeMode == mode,
                            onClick = { onThemeMode(mode) },
                            label = { Text(themeModeLabel(mode)) },
                        )
                    }
                }
                HorizontalDivider()
                SettingHeading(Icons.Default.Palette, stringResource(R.string.color_style))
                ColorSource.entries.forEach { source ->
                    ColorChoice(
                        source = source,
                        selected = preferences.colorSource == source,
                        customSeed = preferences.customSeedArgb,
                        onClick = {
                            if (source == ColorSource.CUSTOM) customEditor = true else onColorSource(source)
                        },
                    )
                }
            }
        }
        if (host != null) {
            item { SectionTitle(stringResource(R.string.connection_settings)) }
            item {
                SettingsCard {
                    SettingHeading(Icons.Default.Computer, host.hostName)
                    LabelValue(stringResource(R.string.status), connectionPhaseLabel(if (connection.host?.hostId == host.hostId) connection.connection else ConnectionPhase.DISCONNECTED))
                    LabelValue(stringResource(R.string.relay_endpoint), host.relayEndpoint ?: "—")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    OutlinedButton(onClick = { relayEditor = host }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.relay_settings))
                    }
                    TextButton(onClick = { forgetTarget = host }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DeleteOutline, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.forget), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.general)) }
        item {
            SettingsCard {
                SettingsAction(Icons.Default.Notifications, stringResource(R.string.notification_settings)) {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
                HorizontalDivider()
                SettingHeading(Icons.Default.Info, stringResource(R.string.about))
                LabelValue(stringResource(R.string.version), BuildConfig.VERSION_NAME)
            }
        }
    }
    relayEditor?.let { editing ->
        RelaySettingsDialog(
            host = editing,
            onDismiss = { relayEditor = null },
            onSave = { endpoint -> onConfigureRelay(editing.hostId, endpoint); relayEditor = null },
        )
    }
    if (customEditor) {
        CustomColorDialog(preferences.customSeedArgb, { customEditor = false }) {
            onCustomSeed(it)
            customEditor = false
        }
    }
    forgetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text(stringResource(R.string.forget_host_title, target.hostName)) },
            text = { Text(stringResource(R.string.forget_host_warning)) },
            confirmButton = {
                TextButton(onClick = { onForget(target.hostId); forgetTarget = null }) {
                    Text(stringResource(R.string.forget), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingHeading(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ColorChoice(source: ColorSource, selected: Boolean, customSeed: Int, onClick: () -> Unit) {
    val preview = when (source) {
        ColorSource.DYNAMIC -> MaterialTheme.colorScheme.primary
        ColorSource.CUSTOM -> Color(customSeed)
        else -> Color(source.seedArgb!!)
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = preview, modifier = Modifier.size(28.dp)) {}
        Text(colorSourceLabel(source), modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CustomColorDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember(current) { mutableStateOf(formatHexColor(current)) }
    val parsed = parseHexColor(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.custom_color_hint))
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 7) value = it.uppercase(Locale.ROOT) },
                    label = { Text("#RRGGBB") },
                    isError = parsed == null,
                    supportingText = if (parsed == null) ({ Text(stringResource(R.string.invalid_hex_color)) }) else null,
                    singleLine = true,
                )
                parsed?.let { color ->
                    Surface(color = Color(color), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(56.dp)) {}
                }
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RelaySettingsDialog(host: HostProfile, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
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
                    placeholder = { Text("relay.example.com:2333") },
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
private fun FormCard(form: FormPrompt, onSubmit: (Map<String, FormAnswer>) -> Unit) {
    var answers by remember(form.id) { mutableStateOf<Map<String, FormAnswer>>(emptyMap()) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(form.prompt, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(formatTimestamp(form.requestedAt), style = MaterialTheme.typography.labelSmall)
            }
            form.fields.forEach { field ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(field.header, style = MaterialTheme.typography.labelLarge)
                    Text(field.prompt, style = MaterialTheme.typography.bodyMedium)
                    field.options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = form.interactive) {
                                answers = answers + (field.id to FormAnswer.Choice(option.id))
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (answers[field.id] as? FormAnswer.Choice)?.optionId == option.id,
                                onClick = if (form.interactive) {
                                    {
                                    answers = answers + (field.id to FormAnswer.Choice(option.id))
                                    }
                                } else null,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(option.label)
                                option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (field.allowsOther) {
                        val currentText = (answers[field.id] as? FormAnswer.Text)?.text.orEmpty()
                        OutlinedTextField(
                            value = currentText,
                            onValueChange = { answers = answers + (field.id to FormAnswer.Text(it)) },
                            enabled = form.interactive,
                            singleLine = !field.options.isEmpty(),
                            visualTransformation = if (field.sensitive) PasswordVisualTransformation() else VisualTransformation.None,
                            label = { Text(if (field.options.isEmpty()) stringResource(R.string.form_answer) else stringResource(R.string.form_other)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            form.submissionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (form.submissionState == ApprovalSubmissionState.SUBMITTING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.form_submitting))
                }
            } else if (form.interactive) {
                Button(
                    onClick = { onSubmit(answers) },
                    enabled = form.fields.all { field ->
                        when (val answer = answers[field.id]) {
                            is FormAnswer.Choice -> field.options.any { it.id == answer.optionId }
                            is FormAnswer.Text -> field.allowsOther && answer.text.isNotBlank()
                            null -> false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.form_submit)) }
            } else {
                Text(stringResource(R.string.form_read_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: ApprovalPrompt, onSubmit: (String) -> Unit) {
    var confirmingOptionId by remember(approval.id) { mutableStateOf<String?>(null) }
    val confirming = approval.options.firstOrNull { it.id == confirmingOptionId }
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.semanticColors.warningContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.semanticColors.warning)
                Spacer(Modifier.width(8.dp))
                Text(approval.prompt, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(formatTimestamp(approval.requestedAt), style = MaterialTheme.typography.labelSmall)
            when (val subject = approval.subject) {
                is ApprovalSubject.Command -> {
                    Text(if (subject.kind == "write_stdin") stringResource(R.string.approval_write_stdin) else stringResource(R.string.approval_command), style = MaterialTheme.typography.labelLarge)
                    subject.command?.let { CodeSurface(it) }
                    subject.cwd?.let { LabelValue(stringResource(R.string.approval_cwd), it) }
                    subject.reason?.let { LabelValue(stringResource(R.string.approval_reason), it) }
                    subject.network?.let { LabelValue(stringResource(R.string.approval_network), "${it.protocol}://${it.host}") }
                }
                is ApprovalSubject.FileChange -> {
                    Text(stringResource(R.string.approval_file_changes), style = MaterialTheme.typography.labelLarge)
                    subject.grantRoot?.let { LabelValue(stringResource(R.string.approval_grant_root), it) }
                    subject.reason?.let { LabelValue(stringResource(R.string.approval_reason), it) }
                    subject.changes.forEach { change -> CodeSurface("${change.kind} · ${change.path}\n${change.diff}") }
                }
            }
            approval.unavailableReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            approval.submissionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (approval.submissionState == ApprovalSubmissionState.SUBMITTING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.approval_submitting))
                }
            } else if (approval.interactive && approval.options.isNotEmpty()) {
                approval.options.forEach { option ->
                    if (option.disposition == "approve") {
                        Button(onClick = { confirmingOptionId = option.id }, modifier = Modifier.fillMaxWidth()) { Text(option.label) }
                    } else {
                        OutlinedButton(onClick = { confirmingOptionId = option.id }, modifier = Modifier.fillMaxWidth()) { Text(option.label) }
                    }
                    option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            } else if (approval.unavailableReason == null) {
                Text(stringResource(R.string.approval_read_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    confirming?.let { option ->
        AlertDialog(
            onDismissRequest = { confirmingOptionId = null },
            title = { Text(option.label) },
            text = { Text(option.description ?: stringResource(R.string.approval_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmingOptionId = null; onSubmit(option.id) }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmingOptionId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CodeSurface(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusDot(phase: ConnectionPhase) {
    val color = when (phase) {
        ConnectionPhase.CONNECTED -> MaterialTheme.semanticColors.success
        ConnectionPhase.CONNECTING, ConnectionPhase.RETRYING -> MaterialTheme.semanticColors.warning
        ConnectionPhase.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(10.dp).background(color, MaterialTheme.shapes.extraLarge))
}

@Composable
private fun StatusPill(label: String, phase: ConnectionPhase) {
    val (foreground, background) = when (phase) {
        ConnectionPhase.CONNECTED -> MaterialTheme.semanticColors.success to MaterialTheme.semanticColors.successContainer
        ConnectionPhase.CONNECTING, ConnectionPhase.RETRYING -> MaterialTheme.semanticColors.warning to MaterialTheme.semanticColors.warningContainer
        ConnectionPhase.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = background) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = foreground, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SessionStatePill(state: String) {
    val semantic = MaterialTheme.semanticColors
    val (foreground, background) = when (state) {
        "running", "initializing" -> semantic.success to semantic.successContainer
        "waiting_for_interaction" -> semantic.warning to semantic.warningContainer
        "failed", "cancelled" -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
        "completed" -> semantic.info to semantic.infoContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = background) {
        Text(sessionStateLabel(state), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = foreground, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PairingDialog(phase: PairingPhase, message: String?, onDismiss: () -> Unit) {
    val terminal = phase == PairingPhase.SUCCEEDED || phase == PairingPhase.FAILED
    AlertDialog(
        onDismissRequest = { if (terminal) onDismiss() },
        title = {
            Text(stringResource(when (phase) {
                PairingPhase.WAITING_FOR_HOST -> R.string.waiting_for_host
                PairingPhase.FAILED -> R.string.pairing_failed
                else -> R.string.pairing
            }))
        },
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
private fun ScannerDialog(onDismiss: () -> Unit, onQr: (String) -> Unit, onError: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.scan_qr), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
                QrScanner(Modifier.fillMaxSize(), onQr, onError)
            }
        }
    }
}

internal fun sessionsNewestFirst(sessions: Collection<SessionView>): List<SessionView> =
    sessions.sortedWith(compareByDescending<SessionView> { it.session.updatedAt }.thenByDescending { it.cursor })

internal fun eventsNewestFirst(events: List<EventRecord>): List<EventRecord> = events.sortedByDescending(EventRecord::sequence)

internal fun filterSessions(sessions: Collection<SessionView>, query: String, filter: SessionFilter): List<SessionView> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    return sessionsNewestFirst(sessions).filter { view ->
        val matchesFilter = when (filter) {
            SessionFilter.ALL -> true
            SessionFilter.RUNNING -> view.session.state == "running" || view.session.state == "initializing"
            SessionFilter.WAITING -> view.session.state == "waiting_for_interaction"
            SessionFilter.FINISHED -> view.session.state in setOf("completed", "failed", "cancelled")
        }
        val matchesQuery = normalized.isEmpty() || listOfNotNull(
            view.session.title,
            view.session.externalId,
            view.session.workspaceName,
            view.session.workspacePath,
        ).any { normalized in it.lowercase(Locale.ROOT) }
        matchesFilter && matchesQuery
    }
}

private fun SessionView.displayTitle(): String = session.title ?: session.externalId ?: session.id

private fun formatTimestamp(value: String): String = try {
    val local = OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())
    local.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
} catch (_: DateTimeParseException) {
    value
}

@Composable
private fun connectionPhaseLabel(phase: ConnectionPhase): String = stringResource(when (phase) {
    ConnectionPhase.DISCONNECTED -> R.string.disconnected
    ConnectionPhase.CONNECTING -> R.string.connecting
    ConnectionPhase.CONNECTED -> R.string.connected
    ConnectionPhase.RETRYING -> R.string.retrying
})

@Composable
private fun sessionStateLabel(state: String): String = stringResource(when (state) {
    "initializing" -> R.string.state_initializing
    "idle" -> R.string.state_idle
    "running" -> R.string.state_running
    "waiting_for_interaction" -> R.string.state_waiting
    "completed" -> R.string.state_completed
    "failed" -> R.string.state_failed
    "cancelled" -> R.string.state_cancelled
    else -> R.string.state_unknown
})

@Composable
private fun sessionFilterLabel(filter: SessionFilter): String = stringResource(when (filter) {
    SessionFilter.ALL -> R.string.filter_all
    SessionFilter.RUNNING -> R.string.filter_running
    SessionFilter.WAITING -> R.string.filter_waiting
    SessionFilter.FINISHED -> R.string.filter_finished
})

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
})

@Composable
private fun colorSourceLabel(source: ColorSource): String = stringResource(when (source) {
    ColorSource.DYNAMIC -> R.string.color_dynamic
    ColorSource.INDIGO -> R.string.color_indigo
    ColorSource.VIOLET -> R.string.color_violet
    ColorSource.TEAL -> R.string.color_teal
    ColorSource.ORANGE -> R.string.color_orange
    ColorSource.ROSE -> R.string.color_rose
    ColorSource.CUSTOM -> R.string.custom_color
})

@Composable
private fun eventTitle(event: EventRecord): String = stringResource(when (event.type) {
    "session_started" -> R.string.event_session_started
    "state_changed" -> R.string.event_state_changed
    "connection_changed" -> R.string.event_connection_changed
    "message" -> R.string.event_message
    "tool_activity" -> R.string.event_tool_activity
    "plan_updated" -> R.string.event_plan_updated
    "progress_updated" -> R.string.event_progress_updated
    "interaction_requested" -> R.string.event_interaction_requested
    "interaction_responded" -> R.string.event_interaction_responded
    "interaction_closed" -> R.string.event_interaction_closed
    "command_issued" -> R.string.event_command_issued
    else -> R.string.event_session_ended
})

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope
