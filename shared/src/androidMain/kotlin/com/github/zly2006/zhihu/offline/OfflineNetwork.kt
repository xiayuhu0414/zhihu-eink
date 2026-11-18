package com.github.zly2006.zhihu.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Some VPNs retain VALIDATED after Wi-Fi disappears; require a live underlying bearer too. */
@Suppress("DEPRECATION")
fun hasOfflineNetwork(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val active = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    if (!active.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
    if (!active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
    return manager.allNetworks.any { network ->
        val capabilities = manager.getNetworkCapabilities(network)
        capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
