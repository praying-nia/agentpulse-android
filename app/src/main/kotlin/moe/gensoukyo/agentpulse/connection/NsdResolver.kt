package moe.gensoukyo.agentpulse.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class ResolvedEndpoint(val address: String, val port: Int)

internal class NsdResolver(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)

    suspend fun resolve(hostId: String): ResolvedEndpoint? = withTimeoutOrNull(4_000) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var discovery: NsdManager.DiscoveryListener

            fun finish(value: ResolvedEndpoint?) {
                if (completed.compareAndSet(false, true)) {
                    runCatching { manager.stopServiceDiscovery(discovery) }
                    continuation.resume(value)
                }
            }

            val resolver = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                @Suppress("DEPRECATION")
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val advertisedHost = serviceInfo.attributes["host_id"]?.decodeToString()
                    if (advertisedHost != hostId) return
                    val address = if (Build.VERSION.SDK_INT >= 34) {
                        serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                    } else {
                        serviceInfo.host?.hostAddress
                    }
                    if (address != null && serviceInfo.port in 1..65535) finish(ResolvedEndpoint(address, serviceInfo.port))
                }
            }

            discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                @Suppress("DEPRECATION")
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.startsWith(SERVICE_TYPE)) manager.resolveService(serviceInfo, resolver)
                }
            }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) runCatching { manager.stopServiceDiscovery(discovery) }
            }
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_agentpulse._tcp."
    }
}
