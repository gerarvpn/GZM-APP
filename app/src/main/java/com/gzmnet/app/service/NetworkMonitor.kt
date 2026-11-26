package com.gzmnet.app.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.gzmnet.app.AppConfig
import com.gzmnet.app.extension.delay
import com.gzmnet.app.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] is invoked on a background thread after the debounce window and may block.
 */
internal fun shouldAcceptUnderlyingNetwork(isVpn: Boolean, hasInternet: Boolean): Boolean = !isVpn && hasInternet
internal fun shouldClearLostUpstream(lostIsCurrent: Boolean): Boolean = lostIsCurrent
internal fun shouldRunNetworkHandover(isCurrentMonitor: Boolean, coreRunning: Boolean): Boolean = isCurrentMonitor && coreRunning

class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: () -> Unit,
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L
    }

    @Volatile private var upstream: Network? = null
    private var handoverJob: Job? = null
    @Volatile private var registered = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            // Never let the VPN interface itself be selected as its own physical upstream.
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivity.getNetworkCapabilities(network) ?: return
            if (!shouldAcceptUnderlyingNetwork(
                    isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                )) return
            val previous = upstream
            upstream = network
            onUnderlyingNetworksChanged(arrayOf(network))
            if (previous != null && previous != network) scheduleHandover(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (network != upstream) return
            if (!shouldAcceptUnderlyingNetwork(
                    isVpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                )) {
                upstream = null
                onUnderlyingNetworksChanged(null)
                return
            }
            onUnderlyingNetworksChanged(arrayOf(network))
        }

        override fun onLost(network: Network) {
            if (shouldClearLostUpstream(network == upstream)) {
                upstream = null
                onUnderlyingNetworksChanged(null)
            }
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        if (registered) return
        try {
            connectivity.requestNetwork(request, callback)
            registered = true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to request network", e)
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        val wasRegistered = registered
        registered = false
        handoverJob?.cancel()
        handoverJob = null
        scope.coroutineContext.cancelChildren()
        upstream = null
        if (!wasRegistered) return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
        }
    }

    private fun scheduleHandover(network: Network) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network")
        handoverJob?.cancel()
        handoverJob = scope.launch {
            try {
                delay(HANDOVER_DEBOUNCE_MS)
                if (registered && upstream == network) onHandover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change", e)
            }
        }
    }
}
