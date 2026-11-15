package dev.openhost.app.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class NetworkAddresses(
    val lanIp: String = "",
    val tailscaleIp: String = "",
)

fun getNetworkAddresses(): NetworkAddresses {
    var lanIp = ""
    var tailscaleIp = ""
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (iface in interfaces) {
            val addresses = Collections.list(iface.inetAddresses)
            for (addr in addresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    val name = iface.name.lowercase()
                    val isTailscale = name == "tun0" || isTailscaleRange(ip)
                    when {
                        isTailscale -> if (tailscaleIp.isBlank()) tailscaleIp = ip
                        name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("rmnet") -> {
                            if (lanIp.isBlank()) lanIp = ip
                        }
                    }
                }
            }
        }
    } catch (_: Exception) { }
    return NetworkAddresses(lanIp = lanIp, tailscaleIp = tailscaleIp)
}

private fun isTailscaleRange(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    val second = parts[1].toIntOrNull() ?: return false
    return first == 100 && second in 64..127
}
