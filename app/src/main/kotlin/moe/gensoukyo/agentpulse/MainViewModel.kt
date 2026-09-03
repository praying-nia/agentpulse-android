package moe.gensoukyo.agentpulse

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.gensoukyo.agentpulse.connection.ConnectionRuntime
import moe.gensoukyo.agentpulse.connection.ConnectionService
import moe.gensoukyo.agentpulse.connection.PairingClient
import moe.gensoukyo.agentpulse.data.CredentialVault
import moe.gensoukyo.agentpulse.data.ConnectionRoute
import moe.gensoukyo.agentpulse.data.HostProfile
import moe.gensoukyo.agentpulse.protocol.PairingCodec

data class AppState(
    val clientId: String = "",
    val hosts: List<HostProfile> = emptyList(),
    val pairing: PairingPhase = PairingPhase.IDLE,
    val pairingMessage: String? = null,
    val selectedSessionId: String? = null,
)

enum class PairingPhase { IDLE, CONNECTING, WAITING_FOR_HOST, SUCCEEDED, FAILED }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = CredentialVault(application)
    private val mutable = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutable.asStateFlow()
    val connection = ConnectionRuntime.state

    init {
        refresh()
    }

    fun pair(uri: String) {
        viewModelScope.launch {
            val snapshot = vault.snapshot()
            mutable.update { it.copy(pairing = PairingPhase.CONNECTING, pairingMessage = null) }
            runCatching {
                val bundle = PairingCodec.decodeUri(uri)
                PairingClient().pair(
                    bundle = bundle,
                    clientId = snapshot.clientId,
                    displayName = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
                    onPending = { mutable.update { it.copy(pairing = PairingPhase.WAITING_FOR_HOST) } },
                )
            }.onSuccess { profile ->
                vault.upsert(profile)
                mutable.update {
                    it.copy(
                        clientId = snapshot.clientId,
                        hosts = (snapshot.hosts.filterNot { host -> host.hostId == profile.hostId } + profile).sortedBy(HostProfile::hostName),
                        pairing = PairingPhase.SUCCEEDED,
                        pairingMessage = null,
                    )
                }
                connect(profile.hostId)
            }.onFailure { error ->
                Log.e(PAIRING_LOG_TAG, "Pairing failed", error)
                val message = error.message?.takeIf(String::isNotBlank)
                    ?: getApplication<Application>().getString(R.string.pairing_failed_unknown)
                mutable.update { it.copy(pairing = PairingPhase.FAILED, pairingMessage = message) }
            }
        }
    }

    fun clearPairingStatus() = mutable.update { it.copy(pairing = PairingPhase.IDLE, pairingMessage = null) }

    fun connect(hostId: String) {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(
            context,
            Intent(context, ConnectionService::class.java).apply {
                action = ConnectionService.ACTION_CONNECT
                putExtra(ConnectionService.EXTRA_HOST_ID, hostId)
            },
        )
    }

    fun disconnect() {
        val context = getApplication<Application>()
        context.startService(Intent(context, ConnectionService::class.java).apply { action = ConnectionService.ACTION_DISCONNECT })
    }

    fun submitApproval(sessionId: String, interactionId: String, optionId: String) {
        val context = getApplication<Application>()
        context.startService(Intent(context, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_SUBMIT_APPROVAL
            putExtra(ConnectionService.EXTRA_SESSION_ID, sessionId)
            putExtra(ConnectionService.EXTRA_INTERACTION_ID, interactionId)
            putExtra(ConnectionService.EXTRA_OPTION_ID, optionId)
        })
    }

    fun forget(hostId: String) {
        viewModelScope.launch {
            if (connection.value.host?.hostId == hostId) disconnect()
            vault.forget(hostId)
            refresh()
        }
    }

    fun configureRelay(hostId: String, endpoint: String?) {
        viewModelScope.launch {
            val profile = vault.host(hostId) ?: return@launch
            val updated = profile.copy(
                relayEndpoint = endpoint,
                selectedRoute = if (endpoint == null) ConnectionRoute.LAN else profile.selectedRoute,
            )
            vault.upsert(updated)
            refresh()
        }
    }

    fun selectRoute(hostId: String, route: ConnectionRoute) {
        viewModelScope.launch {
            val profile = vault.host(hostId) ?: return@launch
            if (route == ConnectionRoute.RELAY && profile.relayEndpoint == null) return@launch
            vault.upsert(profile.copy(selectedRoute = route))
            refresh()
        }
    }

    fun selectSession(sessionId: String?) = mutable.update { it.copy(selectedSessionId = sessionId) }

    private fun refresh() {
        viewModelScope.launch {
            val snapshot = vault.snapshot()
            mutable.update { it.copy(clientId = snapshot.clientId, hosts = snapshot.hosts) }
        }
    }
}

private const val PAIRING_LOG_TAG = "AgentPulsePairing"
