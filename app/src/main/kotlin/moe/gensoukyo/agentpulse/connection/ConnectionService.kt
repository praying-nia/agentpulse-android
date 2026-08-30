package moe.gensoukyo.agentpulse.connection

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.gensoukyo.agentpulse.MainActivity
import moe.gensoukyo.agentpulse.R
import moe.gensoukyo.agentpulse.data.CredentialVault
import moe.gensoukyo.agentpulse.data.HostProfile
import moe.gensoukyo.agentpulse.protocol.EventImportance
import moe.gensoukyo.agentpulse.protocol.NativeState

class ConnectionService : LifecycleService() {
    private lateinit var notifications: NotificationManager
    private lateinit var vault: CredentialVault
    private var connectionJob: Job? = null
    private var client: NativeWebSocketClient? = null
    private var desiredHostId: String? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        vault = CredentialVault(applicationContext)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_HOST_ID)?.let(::connect)
            ACTION_DISCONNECT -> disconnect()
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        desiredHostId = null
        client?.close()
        connectionJob?.cancel()
        ConnectionRuntime.update(ConnectionSnapshot())
        super.onDestroy()
    }

    private fun connect(hostId: String) {
        disconnect(stopService = false)
        desiredHostId = hostId
        startForeground(ONGOING_ID, ongoingNotification(getString(R.string.connecting)))
        connectionJob = lifecycleScope.launch {
            val snapshot = vault.snapshot()
            var profile = snapshot.hosts.firstOrNull { it.hostId == hostId } ?: run {
                disconnect()
                return@launch
            }
            val delays = intArrayOf(1, 2, 5, 10, 30)
            var attempt = 0
            val resolver = NsdResolver(applicationContext)
            while (isActive && desiredHostId == hostId) {
                profile = vault.host(hostId) ?: run {
                    disconnect()
                    return@launch
                }
                if (attempt > 0) {
                    resolver.resolve(hostId)?.let { endpoint ->
                        profile = profile.copy(lastAddress = endpoint.address, lastPort = endpoint.port)
                    }
                }
                ConnectionRuntime.update(ConnectionSnapshot(profile, ConnectionPhase.CONNECTING))
                notifications.notify(ONGOING_ID, ongoingNotification(getString(R.string.connecting_to, profile.hostName)))
                val socket = NativeWebSocketClient(
                    profile = profile,
                    clientId = snapshot.clientId,
                    onState = { native ->
                        val phase = if (native.phase == NativeState.Phase.LIVE) ConnectionPhase.CONNECTED else ConnectionPhase.CONNECTING
                        ConnectionRuntime.update(ConnectionSnapshot(profile, phase, native))
                    },
                    onEventState = { before, after -> notifyImportantEvents(profile, before, after) },
                )
                client = socket
                socket.connect()
                val failure = socket.awaitClose()
                client = null
                if (!isActive || desiredHostId != hostId) break
                val waitSeconds = delays[attempt.coerceAtMost(delays.lastIndex)]
                attempt = (attempt + 1).coerceAtMost(delays.lastIndex)
                ConnectionRuntime.update(
                    ConnectionSnapshot(
                        host = profile,
                        connection = ConnectionPhase.RETRYING,
                        error = failure?.message ?: getString(R.string.connection_lost, profile.hostName),
                        retrySeconds = waitSeconds,
                    ),
                )
                if (attempt == 1) {
                    notifyAlert(
                        id = hostId.hashCode(),
                        title = getString(R.string.connection_lost, profile.hostName),
                        text = failure?.message,
                        sessionId = null,
                    )
                }
                val jitter = 0.85 + Random.nextDouble() * 0.3
                delay((waitSeconds * 1_000 * jitter).roundToInt().toLong())
            }
        }
    }

    private fun disconnect(stopService: Boolean = true) {
        desiredHostId = null
        client?.close()
        client = null
        connectionJob?.cancel()
        connectionJob = null
        ConnectionRuntime.update(ConnectionSnapshot())
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    private fun notifyImportantEvents(profile: HostProfile, before: NativeState, after: NativeState) {
        after.sessions.forEach { (sessionId, view) ->
            val previous = before.sessions[sessionId]?.cursor ?: 0UL
            view.events.filter { it.sequence > previous }.forEach { event ->
                when (event.importance) {
                    EventImportance.WARNING, EventImportance.ERROR -> notifyAlert(sessionId.hashCode(), profile.hostName, event.detail ?: event.title, sessionId)
                    EventImportance.INTERACTION -> notifyAlert(sessionId.hashCode(), getString(R.string.interaction_waiting, view.session.title ?: profile.hostName), event.detail, sessionId)
                    EventImportance.OUTCOME -> notifyAlert(sessionId.hashCode(), getString(R.string.session_finished, view.session.title ?: profile.hostName), event.detail, sessionId)
                    EventImportance.NORMAL -> Unit
                }
            }
        }
    }

    private fun ongoingNotification(text: String) = NotificationCompat.Builder(this, CONNECTION_CHANNEL)
        .setSmallIcon(R.drawable.ic_agentpulse)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(activityIntent(null))
        .build()

    private fun notifyAlert(id: Int, title: String, text: String?, sessionId: String?) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        notifications.notify(
            id,
            NotificationCompat.Builder(this, EVENT_CHANNEL)
                .setSmallIcon(R.drawable.ic_agentpulse)
                .setContentTitle(title)
                .setContentText(text)
                .setGroup("agentpulse-events")
                .setAutoCancel(true)
                .setContentIntent(activityIntent(sessionId))
                .build(),
        )
    }

    private fun activityIntent(sessionId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(this, sessionId?.hashCode() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createChannels() {
        notifications.createNotificationChannel(NotificationChannel(CONNECTION_CHANNEL, getString(R.string.connection_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.connection_channel_description)
        })
        notifications.createNotificationChannel(NotificationChannel(EVENT_CHANNEL, getString(R.string.events), NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val ACTION_CONNECT = "moe.gensoukyo.agentpulse.CONNECT"
        const val ACTION_DISCONNECT = "moe.gensoukyo.agentpulse.DISCONNECT"
        const val EXTRA_HOST_ID = "host_id"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CONNECTION_CHANNEL = "agentpulse-connection"
        private const val EVENT_CHANNEL = "agentpulse-events"
        private const val ONGOING_ID = 101
    }
}
