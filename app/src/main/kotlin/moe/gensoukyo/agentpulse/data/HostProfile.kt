package moe.gensoukyo.agentpulse.data

import kotlinx.serialization.Serializable

@Serializable
data class HostProfile(
    val hostId: String,
    val hostName: String,
    val serverName: String,
    val caCertificateDer: String,
    val accessToken: String,
    val lastAddress: String,
    val lastPort: Int,
)

@Serializable
internal data class VaultPayload(
    val schemaVersion: Int = 1,
    val clientId: String,
    val hosts: List<HostProfile> = emptyList(),
)
