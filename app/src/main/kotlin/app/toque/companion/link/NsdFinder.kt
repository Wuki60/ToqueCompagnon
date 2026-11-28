package app.toque.companion.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address

/**
 * Les robots Toque annoncés sur le Wi-Fi (mDNS / DNS-SD, service « _toque._tcp », que le robot annonce tant
 * que son serveur du compagnon est ouvert). Leurs adresses passent avant le balayage du /24 du téléphone : le
 * robot se retrouve plus vite, et aussi sur un réseau plus grand qu'un /24. Une recherche courte et bornée
 * ([find]), sur le fil de l'appelant, jamais le fil principal. Seulement des adresses IPv4 privées, comme le
 * balayage ; chacune doit ensuite passer la preuve du robot, le jeton n'est envoyé à aucune. Rien quand le
 * téléphone n'a pas le service (tests, Android sans NSD) : le balayage seul, comme avant.
 */
class NsdFinder(context: Context) {
    private val nsd: NsdManager? = try {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (_: Throwable) {
        null
    }

    /**
     * Adresses des robots annoncés. Rend la main dès qu'une adresse est résolue et que plus rien n'est en
     * cours (au moins [settleMs] après le début, pour laisser répondre un second robot), au plus tard après [waitMs].
     */
    fun find(waitMs: Long = 2_500, settleMs: Long = 600): List<String> {
        val m = nsd ?: return emptyList()
        val lock = Any()
        val found = LinkedHashSet<String>()
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var stopped = false

        // Une résolution à la fois : les Android d'avant 14 refusent la seconde (FAILURE_ALREADY_ACTIVE).
        fun next() {
            if (resolving || stopped) return
            val s = pending.removeFirstOrNull() ?: return
            resolving = true
            val done = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) = synchronized(lock) {
                    resolving = false
                    next()
                }

                override fun onServiceResolved(info: NsdServiceInfo) = synchronized(lock) {
                    @Suppress("DEPRECATION")
                    val a = info.host
                    if (a is Inet4Address && a.isSiteLocalAddress) a.hostAddress?.let { found += it }
                    resolving = false
                    next()
                }
            }
            try {
                @Suppress("DEPRECATION")
                m.resolveService(s, done)
            } catch (_: Throwable) {
                resolving = false
            }
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) = synchronized(lock) {
                if (pending.size < MAX_SERVICES) pending.addLast(service)
                next()
            }
        }
        try {
            m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (_: Throwable) {
            return emptyList()
        }
        val start = System.nanoTime() / 1_000_000L
        try {
            while (true) {
                val elapsed = System.nanoTime() / 1_000_000L - start
                if (elapsed >= waitMs) break
                val settled = synchronized(lock) { found.isNotEmpty() && pending.isEmpty() && !resolving }
                if (settled && elapsed >= settleMs) break
                Thread.sleep(POLL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) { stopped = true }
            try {
                m.stopServiceDiscovery(discovery)
            } catch (_: Throwable) {
                // Découverte déjà arrêtée (échec au démarrage) : rien à faire.
            }
        }
        return synchronized(lock) { found.toList() }
    }

    companion object {
        /** Le service annoncé par le robot (CompanionDiscovery dans Toque). */
        const val SERVICE_TYPE = "_toque._tcp"
        private const val POLL_MS = 100L
        /** Au-delà, des annonces sans intérêt : on n'en résout pas plus. */
        private const val MAX_SERVICES = 16
    }
}
