package com.gymcoach.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.gymcoach.core.repo.NetworkMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Connectivity for the coach (§6.10): a network counts only when the default network has
 * NET_CAPABILITY_VALIDATED (captive portals and dead Wi-Fi are "offline"). Changes are tracked with
 * registerDefaultNetworkCallback (API 24+; minSdk 26). Needs ACCESS_NETWORK_STATE (manifest, WP-01).
 */
class AndroidNetworkMonitor(appContext: Context) : NetworkMonitor {
    private val cm: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val manager = cm ?: return false
        return try {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: SecurityException) {
            false
        }
    }

    override fun observe(): Flow<Boolean> = callbackFlow {
        trySend(isOnline())
        val manager = cm
        if (manager == null) {
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }

            override fun onLost(network: Network) {
                trySend(isOnline())
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
