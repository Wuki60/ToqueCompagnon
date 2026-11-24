package app.toque.companion.link

import app.toque.core.companion.client.StateFollower

/**
 * Le rythme du suivi permanent, pour qu'il coûte peu de batterie les jours où personne ne cuisine.
 * Kotlin pur, testé sur la JVM.
 *
 * - « Actif » : l'appli est à l'écran ([visible]) ou quelque chose est en cours sur le robot ([busy]).
 * - Attente longue : courte quand actif, longue sinon. Un ancien robot ramène l'attente à 25 s : ça
 *   marche aussi, en se réveillant plus souvent.
 * - « Quiet » dès que l'appli n'est pas à l'écran, cuisson ou non : seule une vraie nouvelle du robot
 *   (journal, pause, étape, réglage…) réveille le téléphone, pas un degré ou un gramme de plus. En
 *   arrière-plan personne ne lit la télémesure : les alertes viennent du journal, le minuteur de la
 *   notification est tenu par Android et recalé à chaque réponse (au plus [shortWaitS] pendant une cuisson).
 * - Robot injoignable : actif, 1, 2, 4, 8 puis 15 s ; en arrière-plan, pareil au début puis une
 *   minute, puis [backgroundRetryMs] (les minuteurs n'avancent pas quand le téléphone dort : l'alarme
 *   du service réveille le suiveur, voir [alarmMs]).
 * - Recherche du robot sur tout le /24 : à l'écran, comme avant ; sinon seulement si le téléphone
 *   est sur le même /24 que la dernière adresse du robot (jamais sur le Wi-Fi d'un bureau), et au
 *   plus une fois par [scanEveryMs].
 */
class FollowPacing(
    private val visible: () -> Boolean,
    private val busy: () -> Boolean,
    private val ownIpv4: () -> String?,
    private val robotHost: () -> String?,
    /** Horloge qui avance aussi en veille (SystemClock.elapsedRealtime). */
    private val elapsedMs: () -> Long,
    val shortWaitS: Int = 20,
    val longWaitS: Int = 120,
    val backgroundRetryMs: Long = 5 * 60_000L,
    val scanEveryMs: Long = 60 * 60_000L,
) : StateFollower.Pacing {
    private var lastScan: Long? = null

    val active: Boolean get() = visible() || busy()

    override fun waitSeconds(): Int = if (active) shortWaitS else longWaitS

    /** Appli hors de l'écran : la télémesure (température, poids) ne réveille pas le téléphone. */
    override fun quiet(): Boolean = !visible()

    override fun backoffMs(failures: Int): Long {
        val quick = StateFollower.backoffMs(failures)
        if (active || failures <= 5) return quick
        return if (failures <= 7) 60_000L else backgroundRetryMs
    }

    /** Le chercheur (dernière adresse, puis le /24 si [mayScan]) : après 3 échecs, puis tous les 4. */
    override fun locate(failures: Int): Boolean = failures >= 3 && (failures - 3) % 4 == 0

    /** Sonder tout le /24 maintenant ? Retient l'heure du sondage accordé. */
    @Synchronized
    fun mayScan(): Boolean {
        if (visible()) return true
        val own = ownIpv4() ?: return false
        val host = robotHost() ?: return false
        if (!sameSubnet24(own, host)) return false
        val now = elapsedMs()
        val last = lastScan
        if (last != null && now - last < scanEveryMs) return false
        lastScan = now
        return true
    }

    /** Délai de la prochaine alarme de réveil du service : court tant que le robot ne répond pas. */
    fun alarmMs(reachable: Boolean): Long = if (reachable) 15 * 60_000L else backgroundRetryMs

    companion object {
        fun sameSubnet24(a: String, b: String): Boolean {
            val x = a.split('.')
            val y = b.split('.')
            if (x.size != 4 || y.size != 4 || (x + y).any { it.toIntOrNull() == null }) return false
            return x.take(3) == y.take(3)
        }
    }
}
