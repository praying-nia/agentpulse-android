package moe.gensoukyo.agentpulse

import android.app.Application
import android.content.Intent
import android.os.Build
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
            }.onFailure { error ->
                mutable.update { it.copy(pairing = PairingPhase.FAILED, pairingMessage = error.message) }
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

    fun forget(hostId: String) {
        viewModelScope.launch {
            if (connection.value.host?.hostId == hostId) disconnect()
            vault.forget(hostId)
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
