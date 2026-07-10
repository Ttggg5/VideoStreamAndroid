package com.videostream.local

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    // Android's Wi-Fi/hotspot AP interface is named "wlan0" (also used as the AP
    // interface on many devices), "ap0", or "swlan0" (Samsung). Cellular data
    // interfaces (rmnet*, ccmni*, ...) are never a usable address for a LAN viewer,
    // so interfaces matching these prefixes are tried first.
    private val PREFERRED_INTERFACE_PREFIXES = listOf("wlan", "ap", "swlan")

    /**
     * Best-effort discovery of this device's local (Wi-Fi/hotspot/LAN) IPv4 address,
     * so the user knows what URL to open on another device. Works whether this
     * device is joined to a Wi-Fi network or is itself acting as the hotspot.
     */
    fun getLocalIpAddress(): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { iface -> iface.inetAddresses.asSequence().map { iface to it } }
                .mapNotNull { (iface, addr) -> (addr as? Inet4Address)?.let { iface to it } }
                .filterNot { (_, addr) -> addr.isLinkLocalAddress }
                .toList()

            val preferred = candidates.firstOrNull { (iface, _) ->
                PREFERRED_INTERFACE_PREFIXES.any { prefix -> iface.name.startsWith(prefix, ignoreCase = true) }
            }
            (preferred ?: candidates.firstOrNull())?.second?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
