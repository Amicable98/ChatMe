package com.example.chatme.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Utility helper for inspecting local Wi-Fi connectivity, resolving the device's
 * LAN IPv4 address, and computing the subnet broadcast address for UDP discovery.
 */
object NetworkUtils {

    /**
     * Checks whether the device currently has an active Wi-Fi or local LAN connection.
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                getLocalIpv4Address() != null
    }

    /**
     * Iterates through active network interfaces (preferring wlan0 / eth0) to find
     * the device's site-local IPv4 address on the LAN (e.g. 192.168.1.42).
     */
    fun getLocalIpv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val sortedInterfaces = interfaces.sortedByDescending { iface ->
                val name = iface.name.lowercase()
                when {
                    name.startsWith("wlan") -> 2
                    name.startsWith("eth") || name.startsWith("en") -> 1
                    else -> 0
                }
            }

            for (networkInterface in sortedInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses.toList()
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress
                        if (!hostAddress.isNullOrBlank() && !hostAddress.startsWith("169.254")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore interface enumeration exceptions
        }
        return null
    }

    /**
     * Computes all suitable UDP broadcast addresses for the active LAN interface,
     * including the interface's directed subnet broadcast (e.g., 192.168.1.255)
     * and the global fallback broadcast (255.255.255.255).
     */
    fun getBroadcastAddresses(): List<InetAddress> {
        val result = mutableSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        result.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback below
        }

        try {
            result.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
            // Ignore
        }
        return result.toList()
    }

    /**
     * Acquires a WifiManager.MulticastLock so Android's Wi-Fi chipset does not filter out
     * incoming UDP broadcast packets while discovery is running.
     */
    fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.createMulticastLock("ChatMeUdpDiscoveryLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }
}
