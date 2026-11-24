package app.toque.companion.alerts

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.live.LivePresenter
import app.toque.core.companion.StateWire

/** Où vont les alertes (les notifications Android, ou une liste dans les tests). */
interface AlertSink {
    /** [onScreen] : l'appli est à l'écran (notification brève, l'écran montre déjà l'événement). */
    fun show(alert: Alert, robotName: String, onScreen: Boolean)
    fun showOffline(robotName: String, onScreen: Boolean)
    fun clearOffline()
}

/**
 * Relie la liaison aux alertes : à chaque état dont le numéro de journal bouge, lit les nouvelles
 * lignes et passe celles à notifier à [sink] ; surveille aussi les pannes (« Robot hors ligne »).
 * Appli à l'écran ([foreground]) ou non, les alertes sont notifiées (un cuisinier ne regarde pas
 * forcément son téléphone) ; à l'écran, la notification s'efface d'elle-même au bout de quelques
 * secondes. Tout arrive sur le fil principal (rappels de [RobotLink]). [log] : le diagnostic. Kotlin pur.
 */
class AlertCenter(
    private val link: RobotLink,
    private val sink: AlertSink,
    private val enabled: () -> Set<AlertKind>,
    private val foreground: () -> Boolean,
    /** Planifie [Runnable] dans [Long] ms sur le fil principal. */
    private val schedule: (Long, Runnable) -> Unit,
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val offline: OfflineWatch = OfflineWatch(),
    private val offlineCheckMs: Long = 5_000,
    private val log: (String) -> Unit = {},
) : RobotLink.Listener {
    private var rules = JournalAlerts()
    private var fetching = false
    private var lastState: StateWire? = null
    private var wasInProgress = false
    private var checking = false
    private var offlineShown = false
    private var follow = -1L
    // En dernier : avec un post synchrone (essais), l'état déjà connu arrive pendant cette ligne.
    private val unsubscribe = link.addListener(this)

    override fun onState(state: StateWire, receivedMono: Long) {
        lastState = state
        wasInProgress = LivePresenter.keepFollowing(state)
        pull()
    }

    override fun onStatus(status: LinkStatus) {
        // Nouveau suivi : on repart de son premier état (rien d'antérieur n'est notifié).
        if (status.follow != follow) {
            log("nouveau suivi n° ${status.follow} : rien d'antérieur ne sera notifié")
            follow = status.follow
            reset()
        }
        when (status.phase) {
            LinkPhase.UNREACHABLE -> checkOffline()
            LinkPhase.CONNECTED -> {
                offline.update(monoMs(), unreachable = false, wasInProgress = wasInProgress)
                if (offlineShown) {
                    offlineShown = false
                    sink.clearOffline()
                }
            }
            LinkPhase.NOT_PAIRED, LinkPhase.REMOVED, LinkPhase.CONNECTING -> {}
        }
    }

    private fun reset() {
        rules = JournalAlerts()
        lastState = null
        wasInProgress = false
    }

    private fun pull() {
        val s = lastState ?: return
        if (fetching) return
        val r = rules
        val after = r.onState(s.journalSeq, s.serverTime) ?: return
        fetching = true
        link.journal(after, 100) { result ->
            fetching = false
            if (r !== rules) return@journal
            result.onSuccess { page ->
                val dropped = ArrayList<String>()
                val alerts = r.onPage(page, enabled(), dropped)
                log("journal lu après $after : ${page.entries.size} ligne(s), ${alerts.size} alerte(s)")
                for (d in dropped) log(d)
                val onScreen = foreground()
                for (a in alerts) {
                    log("alerte ligne ${a.seq} (${a.kind.key})${if (onScreen) ", appli à l'écran" else ""}")
                    sink.show(a, link.status.robotName, onScreen)
                }
            }
            result.onFailure { log("lecture du journal impossible : ${it.javaClass.simpleName}") }
            // Échec : la base n'a pas bougé, on relira au prochain état. Succès : il y a peut-être plus récent.
            if (result.isSuccess && (lastState?.journalSeq ?: 0) > (r.lastSeq ?: 0)) pull()
        }
    }

    private fun checkOffline() {
        if (link.status.phase != LinkPhase.UNREACHABLE) return
        if (offline.update(monoMs(), unreachable = true, wasInProgress = wasInProgress)) {
            if (AlertKind.OFFLINE in enabled()) {
                log("alerte robot hors ligne")
                offlineShown = true
                sink.showOffline(link.status.robotName, foreground())
            } else log("robot hors ligne, alerte désactivée")
        }
        if (!checking) {
            checking = true
            schedule(offlineCheckMs, Runnable {
                checking = false
                checkOffline()
            })
        }
    }

    fun close() = unsubscribe()
}
