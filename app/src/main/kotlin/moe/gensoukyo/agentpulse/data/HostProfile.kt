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
    val relayEndpoint: String? = null,
    val selectedRoute: ConnectionRoute = ConnectionRoute.LAN,
    val directAddress: String? = null,
    val directPort: Int? = null,
)

@Serializable
enum class ConnectionRoute { LAN, RELAY, DIRECT }

@Serializable
internal data class VaultPayload(
    val schemaVersion: Int = 2,
    val clientId: String,
    val hosts: List<HostProfile> = emptyList(),
)
