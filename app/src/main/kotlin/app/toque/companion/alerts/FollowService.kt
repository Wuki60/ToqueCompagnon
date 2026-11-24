package app.toque.companion.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import app.toque.companion.CompanionApp
import app.toque.companion.link.CommandOutcome
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.live.LivePresenter
import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.StateWire

/**
 * Service de premier plan qui suit le robot en permanence tant qu'un robot est appairé et que le
 * « Suivi en arrière-plan » est sur Oui (écran Robot), appli fermée et téléphone verrouillé compris,
 * pour que les alertes arrivent. Il n'est arrêté que si le téléphone est oublié ou retiré sur le
 * robot, ou si l'interrupteur passe à Non.
 *
 * Notification (Android l'exige tant que le service est au premier plan, et un service ordinaire
 * serait gelé dès l'appli quittée) : pendant une cuisson, celle du canal « Suivi en cours » (titre,
 * minuteur) ; sinon celle, fixe, du canal « Suivi au repos », posée une seule fois à chaque retour au
 * repos ([noticeStep]), que l'utilisateur peut glisser ou masquer dans les réglages d'Android.
 * « En cours » suit la même règle que les verrous ([LockPolicy]).
 *
 * Batterie : processeur et Wi-Fi ne sont tenus éveillés que pendant une cuisson ([LockPolicy]) ;
 * robot au repos, le téléphone dort et la réponse du robot à l'attente longue le réveille (verrou
 * bref pris par l'appli à chaque réponse). Une alarme inexacte ([WakeAlarm]) réveille le suivi de
 * temps en temps, pour réessayer quand le robot est éteint et couper une attente morte.
 *
 * Type « dataSync » (Android 14 : un type est obligatoire). Redémarré par le système (sticky) ;
 * un premier plan refusé par Android est noté dans le diagnostic, jamais un plantage.
 */
class FollowService : Service(), RobotLink.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: CompanionApp
    private var unsubscribe: (() -> Unit)? = null
    private var prefsOff: (() -> Unit)? = null
    private val locks = LockPolicy()
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var following = false
    /** Dernière notification de la cuisson posée (sans son heure de fin) et sa fin prévue. */
    private var lastNotice: CookingNotice? = null
    private var lastEnd = 0L
    /** Une demande d'un bouton de la notification est en route : boutons retirés jusqu'à la réponse. */
    private var sending = false
    /** Refus du robot à montrer à la place du texte, jusqu'à [noteUntil] (horloge monotone). */
    private var note: String? = null
    private var noteUntil = 0L
    private val clearNote = Runnable {
        note = null
        evaluate()
    }
    /** La notification posée au premier plan (null : aucune encore). */
    private var shown: FollowNotice? = null
    /** Dernier changement de notification refusé par Android (une ligne de diagnostic, pas une par état). */
    private var refused: FollowNotice? = null
    /** La notification du repos a été posée sur un canal masqué : Android l'a écartée. */
    private var idleSuppressed = false
    /**
     * Dernière phase annoncée par la liaison. Un état remis à l'inscription peut dater d'avant une
     * coupure (la liaison rejoue son dernier état connu) : il ne dit pas que le robot répond.
     */
    private var phase = LinkPhase.CONNECTING

    private val check = object : Runnable {
        override fun run() {
            if (!wanted(app)) {
                stop("plus de robot appairé ou suivi coupé")
                return
            }
            evaluate()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    private fun mono() = System.nanoTime() / 1_000_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as CompanionApp
        running = true
        current = this
        app.diag.add("service créé")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) app.diag.add("service relancé par Android")
        // Toujours passer au premier plan d'abord : un startForegroundService non suivi de startForeground plante l'appli.
        if (!evaluate(owed = true)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!wanted(app)) {
            stop("rien à suivre (pas de robot appairé ou suivi coupé)")
            return START_NOT_STICKY
        }
        if (!following) {
            following = true
            phase = app.link.status.phase
            unsubscribe = app.link.addListener(this)
            prefsOff = app.alertPrefs.onChanged { if (!app.alertPrefs.background) stop("suivi en arrière-plan coupé") }
            app.link.follow(OWNER)
            handler.postDelayed(check, CHECK_MS)
        }
        WakeAlarm.schedule(this, app.pacing.alarmMs(app.link.status.phase == LinkPhase.CONNECTED))
        return START_STICKY
    }

    /**
     * Recalcule « en cours » ([LockPolicy], même règle pour les verrous et la notification), puis les
     * verrous et la notification. [owed] : un startForeground est dû (démarrage). false : Android a
     * refusé le premier plan.
     */
    private fun evaluate(s: StateWire? = app.link.latest, phase: LinkPhase = app.link.status.phase, owed: Boolean = false): Boolean {
        val busy = locks.hold(mono(), phase == LinkPhase.CONNECTED, LivePresenter.keepFollowing(s))
        updateLocks(busy)
        var step = noticeStep(busy, shown, owed)
        // Canal du repos réaffiché par l'utilisateur : la notification écartée est posée de nouveau, une fois.
        if (!step.post && step.notice == FollowNotice.IDLE && idleSuppressed && !app.notifier.idleHidden()) {
            app.diag.add("notification discrète réaffichée dans les réglages d'Android")
            step = step.copy(post = true)
        }
        if (!step.post) {
            if (step.notice == FollowNotice.COOKING) refreshNotification(s, phase)
            return true
        }
        return foreground(step.notice, s, phase)
    }

    /** Pose [notice] au premier plan (startForeground), et retire l'autre notification du suivi. */
    private fun foreground(notice: FollowNotice, s: StateWire?, phase: LinkPhase): Boolean {
        val before = shown
        val id: Int
        val n = when (notice) {
            FollowNotice.COOKING -> {
                val (notice, end) = describe(s, phase)
                lastNotice = notice
                lastEnd = end
                id = Notifier.FOLLOW_ID
                app.notifier.following(notice, end)
            }
            FollowNotice.IDLE -> {
                id = Notifier.IDLE_ID
                app.notifier.idle()
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(id, n)
            shown = notice
            refused = null
            idleSuppressed = notice == FollowNotice.IDLE && app.notifier.idleHidden()
            // Android retire lui-même l'ancienne en changeant de numéro ; filet sans effet sinon.
            app.notifier.cancel(if (id == Notifier.FOLLOW_ID) Notifier.IDLE_ID else Notifier.FOLLOW_ID)
            if (before != notice) app.diag.add(when (notice) {
                FollowNotice.COOKING -> if (before == null) "premier plan : notification de la cuisson"
                    else "quelque chose a commencé : notification de la cuisson"
                FollowNotice.IDLE -> (if (before == null) "premier plan : notification discrète (rien en cours)"
                    else "rien en cours : notification de la cuisson retirée, notification discrète") +
                    (if (idleSuppressed) " ; masquée dans les réglages d'Android" else "")
            })
            true
        } catch (e: Exception) {
            // Android 12+ : premier plan refusé depuis l'arrière-plan (relance du système…).
            if (refused != notice) app.diag.add("premier plan refusé par Android : ${e.javaClass.simpleName}")
            refused = notice
            Log.w("Toque", "suivi en arrière-plan impossible : $e")
            false
        }
    }

    private fun stop(why: String) {
        app.diag.add("service arrêté : $why")
        stopSelf()
    }

    /**
     * La notification de la cuisson ([CookingNotices], pur), avec le refus d'un bouton à la place du
     * texte quelques secondes, et la fin prévue (horloge du téléphone) si le minuteur avance.
     */
    private fun describe(s: StateWire?, phase: LinkPhase): Pair<CookingNotice, Long> {
        val n = CookingNotices.describe(s, phase, sending)
        val shown = note?.takeIf { mono() < noteUntil }?.let { n.copy(text = it) } ?: n
        val end = if (n.remainingMs > 0) System.currentTimeMillis() + n.remainingMs else 0L
        return shown to end
    }

    private fun refreshNotification(s: StateWire?, phase: LinkPhase) {
        val (notice, end) = describe(s, phase)
        // Fin prévue qui bouge de moins de 2 s : on ne réécrit pas la notification.
        if (notice == lastNotice?.copy(remainingMs = notice.remainingMs) && kotlin.math.abs(end - lastEnd) < 2_000 &&
            (end == 0L) == (lastEnd == 0L)) return
        lastNotice = notice
        lastEnd = end
        app.notifier.update(Notifier.FOLLOW_ID, app.notifier.following(notice, end))
    }

    /** Un bouton de la notification : boutons retirés le temps de la demande, puis la réponse du robot. */
    private fun act(a: NoticeAction) {
        // Le robot ne le permet plus (état changé depuis la notification) : rien n'est envoyé.
        val allowed = CookingNotices.describe(app.link.latest, phase).actions
        if (sending || a !in allowed) {
            app.diag.add("bouton « ${a.name.lowercase()} » de la notification ignoré (plus permis ou déjà en route)")
            evaluate()
            return
        }
        app.diag.add("bouton « ${a.name.lowercase()} » de la notification")
        sending = true
        evaluate()
        send(app, a) { out ->
            sending = false
            if (!out.ok) {
                app.diag.add("bouton « ${a.name.lowercase()} » refusé par le robot (${out.code})")
                note = out.message
                noteUntil = mono() + NOTE_MS
                handler.removeCallbacks(clearNote)
                handler.postDelayed(clearNote, NOTE_MS)
            }
            evaluate()
        }
    }

    // La liaison annonce « connecté » avant de remettre un état qui vient d'arriver ; un état rejoué
    // pendant une coupure garde la phase de la coupure.
    override fun onState(state: StateWire, receivedMono: Long) {
        evaluate(state, phase)
    }

    override fun onStatus(status: LinkStatus) {
        phase = status.phase
        when (status.phase) {
            LinkPhase.NOT_PAIRED -> stop("téléphone oublié")
            LinkPhase.REMOVED -> stop("téléphone retiré sur le robot")
            LinkPhase.UNREACHABLE -> {
                // Réessais espacés en arrière-plan : la prochaine alarme plus tôt.
                WakeAlarm.schedule(this, app.pacing.alarmMs(reachable = false))
                evaluate(app.link.latest, status.phase)
            }
            else -> evaluate(app.link.latest, status.phase)
        }
    }

    /** Verrous de veille et Wi-Fi : tenus seulement pendant une cuisson (et un moment si le robot disparaît en pleine cuisson). */
    @Suppress("DEPRECATION")
    private fun updateLocks(hold: Boolean) {
        try {
            if (hold) {
                val w = wake ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toque:follow")
                    .apply { setReferenceCounted(false) }.also { wake = it }
                if (!w.isHeld) app.diag.add("verrous pris (cuisson en cours)")
                // Filet : tombe de lui-même ; repris à chaque état tant que la cuisson dure.
                w.acquire(MAX_LOCK_MS)
                val f = wifi ?: applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "toque:follow").apply { setReferenceCounted(false) }.also { wifi = it }
                if (!f.isHeld) f.acquire()
            } else releaseLocks()
        } catch (e: Exception) {
            Log.w("Toque", "verrous du suivi : $e")
        }
    }

    private fun releaseLocks() {
        try {
            if (wake?.isHeld == true) app.diag.add("verrous rendus")
            if (wake?.isHeld == true) wake?.release()
            if (wifi?.isHeld == true) wifi?.release()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        running = false
        if (current === this) current = null
        handler.removeCallbacksAndMessages(null)
        unsubscribe?.invoke()
        prefsOff?.invoke()
        if (following) app.link.unfollow(OWNER)
        releaseLocks()
        WakeAlarm.cancel(this)
        app.diag.add("service détruit")
        super.onDestroy()
    }

    companion object {
        private const val OWNER = "service"
        private const val CHECK_MS = 30_000L
        /** Durée du refus d'un bouton affiché dans la notification. */
        const val NOTE_MS = 8_000L
        /** Filet : le verrou de veille tombe de lui-même après 3 h sans nouvel état. */
        private const val MAX_LOCK_MS = 3 * 3_600_000L
        @Volatile var running = false
            private set
        /** Le service en marche (fil principal), pour [recheck]. */
        private var current: FollowService? = null

        /** Recalcule verrous et notification du service en marche (alarme de réveil, retour des réglages…). */
        fun recheck() {
            current?.let { if (wanted(it.app)) it.evaluate() }
        }

        /**
         * Bouton [a] de la notification de la cuisson (depuis [NoticeActionReceiver]) : la même commande
         * que le bouton de l'appli, par la liaison (hors du fil principal) ; le refus du robot s'affiche
         * dans la notification. Sans service en marche (pas de notification à jour) : un message bref.
         */
        fun act(app: CompanionApp, a: NoticeAction) {
            val svc = current
            if (svc != null) return svc.act(a)
            app.diag.add("bouton « ${a.name.lowercase()} » de la notification sans service de suivi")
            send(app, a) { out -> if (!out.ok) Toast.makeText(app, out.message, Toast.LENGTH_LONG).show() }
        }

        private fun send(app: CompanionApp, a: NoticeAction, done: (CommandOutcome) -> Unit) = when (a) {
            NoticeAction.PAUSE -> app.link.pause(done)
            NoticeAction.RESUME -> app.link.resume(done)
            NoticeAction.ADD_MINUTE -> app.link.adjust(AdjustRequest(addMs = 60_000L), done)
        }

        /** Faut-il suivre en arrière-plan : un robot appairé et l'interrupteur sur Oui. */
        fun wanted(app: CompanionApp): Boolean = app.link.paired != null && app.alertPrefs.background

        /**
         * Démarre le suivi en arrière-plan s'il est voulu et ne tourne pas ([why] : pour le diagnostic).
         * Android 12+ refuse un démarrage depuis l'arrière-plan hors exceptions (démarrage du téléphone,
         * mise à jour de l'appli, appli à l'écran, optimisation de batterie levée) : noté, pas de plantage.
         */
        fun start(c: Context, why: String) {
            val app = c.applicationContext as CompanionApp
            if (!wanted(app) || running) return
            app.diag.add("démarrage du service : $why")
            try {
                c.startForegroundService(Intent(c, FollowService::class.java))
            } catch (e: Exception) {
                app.diag.add("démarrage refusé par Android : ${e.javaClass.simpleName}")
                Log.w("Toque", "suivi en arrière-plan impossible : $e")
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, FollowService::class.java))
        }
    }
}

/** Démarrage du téléphone, mise à jour de l'appli : reprendre le suivi (démarrage permis par Android dans ces deux cas). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val why = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "téléphone démarré"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "appli mise à jour"
            else -> return
        }
        val app = context.applicationContext as CompanionApp
        app.diag.add("$why ; ${app.phoneReport()}")
        FollowService.start(context, why)
    }
}

/**
 * Réveil de temps en temps, même en veille (alarme inexacte permise sans autorisation, au plus toutes
 * les ~9 min en sommeil profond) : le suivi réessaie aussitôt, une attente morte est coupée, et le
 * service est relancé s'il a été arrêté (Android peut le refuser : noté).
 */
object WakeAlarm {
    private const val ACTION = "app.toque.companion.WAKE"

    private fun intent(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 0,
        Intent(c, AlarmReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context, delayMs: Long) {
        try {
            c.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, intent(c))
        } catch (e: Exception) {
            Log.w("Toque", "alarme de suivi : $e")
        }
    }

    fun cancel(c: Context) {
        try {
            c.getSystemService(AlarmManager::class.java).cancel(intent(c))
        } catch (_: Exception) {
        }
    }

    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val app = context.applicationContext as CompanionApp
            if (!FollowService.wanted(app)) return
            app.keepAwakeBriefly()
            val phase = app.link.status.phase
            app.diag.add("réveil par alarme (${phase.name.lowercase()})")
            app.link.wake()
            if (!FollowService.running) FollowService.start(context, "alarme") else FollowService.recheck()
            schedule(context, app.pacing.alarmMs(phase == LinkPhase.CONNECTED))
        }
    }
}
