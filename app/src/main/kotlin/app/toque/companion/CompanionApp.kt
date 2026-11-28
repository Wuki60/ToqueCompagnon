package app.toque.companion

import android.app.Application
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import app.toque.companion.alerts.AlertCenter
import app.toque.companion.alerts.AlertPrefs
import app.toque.companion.alerts.DiagLog
import app.toque.companion.alerts.Notifier
import app.toque.companion.flows.RecipePictures
import app.toque.companion.link.FileLinkStore
import app.toque.companion.link.FollowPacing
import app.toque.companion.link.LocalNetwork
import app.toque.companion.link.NsdFinder
import app.toque.companion.link.RobotLink
import app.toque.companion.live.LivePresenter
import app.toque.core.companion.client.RobotFinder
import app.toque.companion.ui.Ui
import app.toque.companion.flows.LookStore
import java.io.File
import android.util.AtomicFile
import app.toque.companion.update.UpdateFlow
import app.toque.companion.update.UpdatePrefs
import app.toque.companion.update.Updater
import java.util.concurrent.Executors

/**
 * L'appli : la liaison avec le robot ([link]) et les alertes, une seule fois pour tout le processus.
 * L'activité et le service de suivi s'en servent ; les écrans à venir (Appairage, Recettes, Journal,
 * Robot) aussi, par `(context.applicationContext as CompanionApp)`.
 */
open class CompanionApp : Application() {
    private val main = Handler(Looper.getMainLooper())
    lateinit var link: RobotLink
        private set
    lateinit var alertPrefs: AlertPrefs
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var alerts: AlertCenter
        private set
    lateinit var pacing: FollowPacing
        private set
    /** Photos des recettes : mémoire et disque pour tout le processus, pas pour une activité. */
    lateinit var pictures: RecipePictures
        private set
    /** Diagnostic des alertes : en mémoire et dans un petit fichier privé de l'appli. */
    val diag = DiagLog()
    private val diagWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-diag").apply { isDaemon = true } }
    private var answerLock: PowerManager.WakeLock? = null

    /** L'activité est à l'écran : le suivi reprend tout de suite au rythme court. */
    @Volatile var visible = false
        set(v) {
            val was = field
            field = v
            if (v != was) diag.add(if (v) "appli à l'écran" else "appli quittée")
            if (::pictures.isInitialized) pictures.setVisible(v)
            if (v && !was && ::link.isInitialized) link.wake(restart = true)
        }

    override fun onCreate() {
        super.onCreate()
        CompanionLanguages.install(this)
        startDiag()
        diag.add("appli lancée ; ${phoneReport()}")
        alertPrefs = AlertPrefs(this)
        pacing = FollowPacing(visible = { visible }, busy = { LivePresenter.keepFollowing(link.latest) },
            ownIpv4 = { LocalNetwork.ownIpv4() }, robotHost = { link.paired?.host }, elapsedMs = { SystemClock.elapsedRealtime() })
        // Appairage (jeton compris) dans le dossier jamais sauvegardé ni transféré.
        // Le robot se cherche d'abord parmi ceux annoncés sur le Wi-Fi (mDNS), puis sur tout le /24 du téléphone.
        val nsd = NsdFinder(this)
        link = RobotLink(FileLinkStore(File(noBackupFilesDir, "robot.json")), post = { main.post(it) },
            candidates = { own -> if (own == null) emptyList() else (nsd.find() + RobotFinder.subnet(own)).distinct() },
            pacing = pacing, elapsedMs = { SystemClock.elapsedRealtime() }, onAnswer = { keepAwakeBriefly() }, log = { diag.add(it) })
        // Apparence du téléphone dès le départ (notification, premier écran).
        lookStore = PrefsLookStore(getSharedPreferences("apparence", MODE_PRIVATE))
        applyLook()
        notifier = Notifier(this) { diag.add(it) }
        alerts = AlertCenter(link, notifier, enabled = { alertPrefs.enabled() }, foreground = { visible },
            schedule = { ms, r -> main.postDelayed(r, ms) }, log = { diag.add(it) })
        pictures = RecipePictures(link, cacheDir)
        pictures.setVisible(visible)
        // Mises à jour depuis GitHub : seule partie de l'appli qui passe par Internet.
        val updater = Updater(this)
        updates = UpdateFlow(updater.installedVersion, updater, PrefsUpdatePrefs(getSharedPreferences("mises-a-jour", MODE_PRIVATE)),
            background = { job -> worker.execute { job() } }, main = { job -> main.post { job() } })
        Updater.onInstallError = { updates.installFailed(it) }
    }

    /** Travail bloquant de l'appli hors du fil principal : mises à jour, liste de courses. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-travail").apply { isDaemon = true } }

    lateinit var updates: UpdateFlow
        private set

    /** Liste de courses : un petit fichier privé, jamais sauvegardé ailleurs ni envoyé au robot. */
    private val cartFile by lazy { AtomicFile(File(filesDir, "courses.json")) }

    fun loadCart(): String? = try {
        String(cartFile.readFully(), Charsets.UTF_8)
    } catch (_: java.io.FileNotFoundException) {
        null
    } catch (e: Exception) {
        diag.add("liste de courses illisible : $e")
        null
    }

    /** Écrit la liste hors du fil principal ; les écritures se suivent dans l'ordre. */
    fun saveCart(json: String) {
        worker.execute {
            var out: java.io.FileOutputStream? = null
            try {
                out = cartFile.startWrite()
                out.write(json.toByteArray(Charsets.UTF_8))
                cartFile.finishWrite(out)
            } catch (e: Exception) {
                out?.let { cartFile.failWrite(it) }
                diag.add("liste de courses non écrite : $e")
            }
        }
    }

    /** Apparence choisie sur le téléphone (thème, couleur), à part de celle du robot. */
    lateinit var lookStore: LookStore
        private set

    /** L'apparence du téléphone dans [Ui] (sombre et orange par défaut). Renvoie true si elle a changé. */
    fun applyLook(): Boolean = Ui.setLook(lookStore.accent, lookStore.theme)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::pictures.isInitialized) pictures.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::pictures.isInitialized) pictures.onLowMemory()
    }

    /**
     * Verrou de veille bref, pris dès qu'une réponse du robot arrive (sur le fil du suiveur) : le
     * temps de lire le journal et de notifier avant que le téléphone se rendorme.
     */
    fun keepAwakeBriefly() {
        try {
            val w = answerLock ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toque:answer")
                .apply { setReferenceCounted(false) }.also { answerLock = it }
            w.acquire(ANSWER_LOCK_MS)
        } catch (_: Exception) {
        }
    }

    /** Version d'Android, notifications permises, limite de batterie : pour le diagnostic. */
    fun phoneReport(): String {
        val notif = try {
            AlertPrefs.permitted(this) && getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        } catch (_: Exception) {
            false
        }
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER}, " +
            "notifications ${if (notif) "permises" else "refusées"}, " +
            "batterie ${if (AlertPrefs.batteryRestricted(this)) "limitée" else "non limitée"}"
    }

    /** Reprend le fichier du diagnostic et y ajoute chaque ligne ; réécrit quand il a trop grossi. */
    private fun startDiag() {
        val file = File(filesDir, "diagnostic.txt")
        try {
            if (file.exists()) diag.restore(file.readLines())
        } catch (_: Exception) {
        }
        var appended = 0
        diag.onLine = { line ->
            try {
                diagWriter.execute {
                    try {
                        if (++appended > diag.capacity) {
                            appended = 0
                            file.writeText(diag.text() + "\n")
                        } else file.appendText(line + "\n")
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val ANSWER_LOCK_MS = 10_000L
    }
}

/** Réglages des mises à jour dans les préférences de l'appli. */
private class PrefsUpdatePrefs(private val p: android.content.SharedPreferences) : UpdatePrefs {
    override var autoCheck: Boolean
        get() = p.getBoolean("auto", true)
        set(v) { p.edit().putBoolean("auto", v).apply() }
    override var lastCheckMs: Long
        get() = p.getLong("derniere", 0L)
        set(v) { p.edit().putLong("derniere", v).apply() }
}
