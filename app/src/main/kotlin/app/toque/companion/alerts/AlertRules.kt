package app.toque.companion.alerts

import app.toque.core.companion.JournalPage

/**
 * Les cinq sortes d'alertes, chacune avec son interrupteur (valeurs par défaut de la maquette,
 * écran Robot : oui, oui, oui, non ; « Température atteinte » : oui). Ajoutée en dernier pour garder
 * les numéros de notification des autres ; l'écran Robot la montre après « Fin d'étape ».
 */
enum class AlertKind(val key: String, val defaultOn: Boolean) {
    /** Le robot attend le cuisinier (pesée, étape à la main, à lancer, recette reçue…). */
    WAITING("waiting", true),
    /** Fin d'une étape ou d'une cuisson. */
    END("end", true),
    /** Pause, couvercle, bol retiré (et alerte machine). */
    PAUSE("pause", true),
    /** Robot hors ligne (détecté par le téléphone). */
    OFFLINE("offline", false),
    /** Température demandée atteinte (cuisson manuelle ou étape de recette, une fois). */
    TEMP("temp", true);

    companion object {
        /** Catégorie d'une ligne du journal (champ `alert`, docs/COMPANION.md, section 5), null si elle n'alerte pas. */
        fun ofJournal(alert: String): AlertKind? = when (alert) {
            "waiting" -> WAITING
            "step_end", "job_end" -> END
            // Une alerte machine arrête tout : avec les pauses (l'arrêt du programme suit en fin de cuisson).
            "pause", "fault" -> PAUSE
            "temp_reached" -> TEMP
            else -> null
        }
    }
}

/** Une notification à montrer : [title] (activité du journal, peut être vide) et [text] (ligne, langue du robot). */
data class Alert(val kind: AlertKind, val seq: Long, val title: String, val text: String)

/**
 * Quelles lignes du journal notifier, une seule fois chacune, et jamais celles d'avant le début du
 * suivi. Kotlin pur ; l'appelant lit le journal quand [onState] le demande, puis passe la page à [onPage].
 *
 * - Au premier état, la ligne la plus récente devient la base : rien d'antérieur n'est notifié.
 * - Ensuite, chaque ligne de numéro plus grand que la base est examinée une fois.
 * - Si le numéro du journal recule, l'appli du robot a redémarré (numéros repartis de 1) : la base
 *   repart de 0, et seules les lignes datées après la dernière ligne vue (horloge du robot) comptent.
 * - Les lignes venues de ce téléphone (`origin.self`) ne sont pas notifiées : on sait ce qu'on a fait.
 * - Une ligne de plus de [maxAgeMs] (horloge du robot : `serverTime` du dernier état contre l'heure de
 *   la ligne) est comptée comme vue sans être notifiée : le suivi est permanent, un téléphone qui
 *   revient à la maison des heures plus tard ne doit pas sonner pour une cuisson finie depuis longtemps.
 */
class JournalAlerts(private val maxAgeMs: Long = 2 * 60_000L) {
    /** Plus grand numéro déjà examiné ; null avant le premier état. */
    var lastSeq: Long? = null
        private set
    /** Horloge du robot : une ligne plus ancienne n'est jamais notifiée. */
    private var floorAt = 0L
    private var lastAt = 0L
    /** Heure du robot au dernier état. */
    private var nowAt = 0L

    /** Nouvel état : numéro après lequel lire le journal, ou null s'il n'y a rien de nouveau. */
    @Synchronized
    fun onState(journalSeq: Long, serverTime: Long): Long? {
        val base = lastSeq
        nowAt = maxOf(nowAt, serverTime)
        if (base == null) {
            lastSeq = journalSeq
            floorAt = serverTime
            lastAt = serverTime
            return null
        }
        if (journalSeq < base) {
            lastSeq = 0
            floorAt = maxOf(floorAt, lastAt + 1)
            return if (journalSeq > 0) 0 else null
        }
        return if (journalSeq > base) base else null
    }

    /**
     * Page lue après [onState] : les alertes à montrer, de la plus ancienne à la plus récente.
     * [dropped] reçoit, pour le diagnostic, chaque ligne d'alerte écartée et pourquoi.
     */
    @Synchronized
    fun onPage(page: JournalPage, enabled: Set<AlertKind>, dropped: MutableList<String>? = null): List<Alert> {
        val base = lastSeq ?: return emptyList()
        val titles = page.activities.associate { it.id to it.title }
        val out = ArrayList<Alert>()
        for (e in page.entries.sortedBy { it.seq }) {
            if (e.seq <= (lastSeq ?: base)) continue
            lastSeq = e.seq
            lastAt = maxOf(lastAt, e.at)
            val kind = AlertKind.ofJournal(e.alert) ?: continue
            val why = when {
                e.at < floorAt -> "antérieure au redémarrage du robot"
                e.origin.self -> "action de ce téléphone"
                nowAt - e.at > maxAgeMs -> "trop ancienne (${(nowAt - e.at) / 1000} s)"
                kind !in enabled -> "alerte désactivée"
                else -> null
            }
            if (why == null) out += Alert(kind, e.seq, titles[e.activityId].orEmpty(), e.text)
            else dropped?.add("ligne ${e.seq} (${e.kind}) écartée : $why")
        }
        return out
    }
}

/**
 * « Robot hors ligne » : une alerte par panne, quand elle dure [delayMs] (un Wi-Fi qui hoquette ne
 * réveille personne), et seulement si quelque chose était en cours sur le robot.
 */
class OfflineWatch(private val delayMs: Long = 30_000) {
    private var since: Long? = null
    private var fired = false

    /** true une seule fois par panne, au moment de notifier. */
    @Synchronized
    fun update(nowMono: Long, unreachable: Boolean, wasInProgress: Boolean): Boolean {
        if (!unreachable) {
            since = null
            fired = false
            return false
        }
        val s = since ?: nowMono.also { since = it }
        if (!fired && wasInProgress && nowMono - s >= delayMs) {
            fired = true
            return true
        }
        return false
    }
}

/**
 * Quand le service de suivi garde le processeur et le Wi-Fi éveillés : seulement pendant que quelque
 * chose est en cours sur le robot, et un moment encore s'il ne répond plus en pleine cuisson (le temps
 * de prévenir « Robot hors ligne » et de le retrouver). Robot au repos : rien, le téléphone dort et la
 * réponse du robot le réveille.
 */
class LockPolicy(private val offlineHoldMs: Long = 10 * 60_000L) {
    private var lastBusy: Long? = null

    @Synchronized
    fun hold(nowMono: Long, reachable: Boolean, inProgress: Boolean): Boolean {
        if (reachable) {
            lastBusy = if (inProgress) nowMono else null
            return inProgress
        }
        val busy = lastBusy ?: return false
        return nowMono - busy < offlineHoldMs
    }
}

/** La notification que montre le service de suivi : celle de la cuisson, ou celle, fixe et discrète, du repos. */
enum class FollowNotice { COOKING, IDLE }

/** [notice] : la notification voulue ; [post] : la poser maintenant (startForeground). */
data class NoticeStep(val notice: FollowNotice, val post: Boolean)

/**
 * Quelle notification le service de suivi garde au premier plan. Kotlin pur.
 *
 * - Quelque chose en cours ([busy], la règle de [LockPolicy]) : celle de la cuisson ; sinon celle du repos.
 * - Posée quand rien n'est encore montré ([shown] null), quand on passe de l'une à l'autre, et quand
 *   un premier plan est dû ([owed] : démarrage par startForegroundService).
 * - Jamais reposée sinon : la notification du repos ne change pas tant que rien ne commence, et un
 *   glissement pour l'effacer tient. (Celle de la cuisson est mise à jour à part, texte et minuteur.)
 */
fun noticeStep(busy: Boolean, shown: FollowNotice?, owed: Boolean): NoticeStep {
    val want = if (busy) FollowNotice.COOKING else FollowNotice.IDLE
    return NoticeStep(want, owed || shown != want)
}
