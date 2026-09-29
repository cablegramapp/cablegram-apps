package app.cablegram.data

import java.net.Inet4Address
import java.net.NetworkInterface

fun localIpv4Addresses(): List<String> {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
    val found = mutableListOf<String>()
    for (nic in interfaces) {
        if (!nic.isUp || nic.isLoopback) continue
        for (address in nic.inetAddresses) {
            if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                address.hostAddress?.let(found::add)
            }
        }
    }
    return found.distinct()
}

fun localIpv4(): String? = localIpv4Addresses().firstOrNull()
