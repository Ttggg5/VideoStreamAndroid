package com.videostream.local

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Best-effort discovery of this device's local (Wi-Fi/hotspot/LAN) IPv4 address,
     * so the user knows what URL to open on another device.
     */
    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
