package com.tonbiattack.instantmic.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A receiver discovered on the local network. */
data class DiscoveredReceiver(
    val serverId: String,
    val serverName: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val hasTls: Boolean,
) {
    /** True when this record describes the same PC as a stored pairing. */
    fun matches(serverId: String?): Boolean = serverId != null && serverId == this.serverId
}

/**
 * Watches for receivers advertising `_instantmic._tcp` on the LAN.
 *
 * Discovery carries no secret and authenticates nothing: it only tells the app
 * which address and certificate fingerprint to try. The stored per-device token
 * is still required to send anything, so a discovered PC cannot be driven by an
 * unpaired phone.
 *
 * Android's NsdManager is used instead of a custom mDNS client because it works
 * without multicast permissions quirks across vendors. When discovery is
 * unavailable the app falls back to the last known address.
 */
class DiscoveryClient(private val context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /** Emits every receiver as it is resolved. The flow stops on cancel. */
    fun discover(): Flow<DiscoveredReceiver> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            close()
            return@callbackFlow
        }

        val resolveListeners = mutableListOf<NsdManager.ResolveListener>()
        val resolving = mutableSetOf<String>()

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName
                if (!resolving.add(name)) return
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving.remove(name)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        resolving.remove(name)
                        toReceiver(info)?.let { trySend(it) }
                    }
                }
                resolveListeners += listener
                try {
                    manager.resolveService(info, listener)
                } catch (e: IllegalArgumentException) {
                    // The service disappeared between discovery and resolve.
                    resolving.remove(name)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            close()
        }

        awaitClose {
            try {
                manager.stopServiceDiscovery(discovery)
            } catch (_: Exception) {
            }
            resolveListeners.clear()
        }
    }

    private fun toReceiver(info: NsdServiceInfo): DiscoveredReceiver? {
        val attributes = info.attributes ?: emptyMap()
        val serverId = attributes["id"]?.let(::decode)?.trim().orEmpty()
        val host = info.host?.hostAddress ?: return null
        if (serverId.isEmpty() || info.port <= 0) return null

        return DiscoveredReceiver(
            serverId = serverId,
            serverName = attributes["name"]?.let(::decode)?.ifEmpty { null } ?: info.serviceName,
            host = host,
            port = info.port,
            fingerprint = attributes["fp"]?.let(::decode).orEmpty().lowercase(),
            hasTls = attributes["tls"]?.let(::decode) != "0",
        )
    }

    private fun decode(bytes: ByteArray): String =
        try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }

    companion object {
        private const val TAG = "InstantMicDiscovery"
        const val SERVICE_TYPE = "_instantmic._tcp."
    }
}
