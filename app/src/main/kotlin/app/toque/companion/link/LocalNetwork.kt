package app.toque.companion.link

import java.net.Inet4Address
import java.net.NetworkInterface

/** L'adresse IPv4 du téléphone sur le réseau de la maison (sans permission : interfaces réseau de Java). */
object LocalNetwork {
    /**
     * Adresse privée (10/8, 172.16/12, 192.168/16) d'une interface active, le Wi-Fi (wlan…) d'abord ;
     * null hors Wi-Fi (données mobiles seules : adresses de l'opérateur, jamais sondées).
     */
    fun ownIpv4(): String? {
        val found = ArrayList<Pair<String, String>>()
        try {
            val list = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in list) {
                try {
                    if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                } catch (_: Exception) {
                    continue
                }
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) found += ni.name.orEmpty() to (a.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return pick(found)
    }

    /** Choix parmi (interface, adresse) : le Wi-Fi d'abord, puis l'Ethernet, puis le reste sauf les données mobiles. */
    fun pick(found: List<Pair<String, String>>): String? {
        fun rank(name: String) = when {
            name.startsWith("wlan") || name.startsWith("wifi") -> 0
            name.startsWith("eth") -> 1
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> 9
            else -> 2
        }
        return found.filter { rank(it.first) < 9 }.minByOrNull { rank(it.first) }?.second
    }
}
