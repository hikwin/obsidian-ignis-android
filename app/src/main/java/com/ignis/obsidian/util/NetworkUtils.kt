package com.ignis.obsidian.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * 获取设备的本地局域网 IPv4 地址（如 192.168.x.x）
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val ipCandidates = mutableListOf<String>()

            for (networkInterface in interfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val hostAddress = address.hostAddress ?: continue
                        // 优先返回 wlan0 或常见 Wi-Fi 网段
                        if (networkInterface.name.contains("wlan") || networkInterface.name.contains("ap")) {
                            return hostAddress
                        }
                        ipCandidates.add(hostAddress)
                    }
                }
            }
            if (ipCandidates.isNotEmpty()) {
                return ipCandidates.first()
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
