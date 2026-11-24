package app.toque.companion

import app.toque.companion.flows.CoursesFlow
import app.toque.companion.flows.LanguageChoice
import app.toque.companion.update.UpdatePhase
import app.toque.companion.update.UpdateView
import app.toque.companion.ui.ConfirmSheet
import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.toque.companion.alerts.AlertPrefs
import app.toque.companion.alerts.FollowService
import app.toque.companion.alerts.Notifier
import app.toque.companion.flows.AppearanceFlow
import app.toque.companion.flows.BackgroundFollow
import app.toque.companion.flows.LookOffer
import app.toque.companion.flows.LookView
import app.toque.companion.flows.RobotFollow
import app.toque.companion.flows.CatalogFlow
import app.toque.companion.flows.DraftStore
import app.toque.companion.flows.EditFlow
import app.toque.companion.flows.JournalFlow
import app.toque.companion.flows.PairingFlow
import app.toque.companion.flows.PhoneClock
import app.toque.companion.flows.RobotFlow
import app.toque.companion.flows.Scheduler
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.live.LiveController
import app.toque.companion.ui.ChoiceSheet
import app.toque.companion.ui.Route
import app.toque.companion.ui.Screens
import app.toque.companion.ui.Shell
import app.toque.companion.ui.Ui
import app.toque.companion.ui.screens.CameraAccess
import app.toque.companion.ui.screens.DiagnosticState
import app.toque.core.i18n.tr

/**
 * L'unique activité : la coquille (écrans et onglets), « En direct » suivi par [LiveController],
 * les autres onglets par leurs flux ([CatalogFlow], [JournalFlow], [RobotFlow]) ; l'écran
 * Appairage en plein écran tant qu'aucun robot n'est appairé ([PairingFlow]).
 */
class CompanionActivity : Activity(), RobotLink.Listener {
    lateinit var shell: Shell
        private set
    lateinit var screens: Screens
        private set
    lateinit var live: LiveController
        private set
    lateinit var pairing: PairingFlow
        private set
    lateinit var catalog: CatalogFlow
        private set
    lateinit var journal: JournalFlow
        private set
    lateinit var robot: RobotFlow
        private set
    lateinit var editor: EditFlow
        private set
    lateinit var appearance: AppearanceFlow
        private set
    lateinit var courses: CoursesFlow
        private set
    private val app get() = application as CompanionApp
    private val main = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        CompanionLanguages.install(this)
        val link = app.link
        // Apparence du téléphone avant le premier écran : ni orange ni sombre en passant.
        app.applyLook()
        lookShown = Ui.lookSeq
        applyWindow()
        shell = Shell(this)
        val clock = PhoneClock()
        val schedule: Scheduler = { ms, r -> main.postDelayed(r, ms); { main.removeCallbacks(r) } }
        live = LiveController(link, shell)
        pairing = PairingFlow(link, { screens.appairage.render(it) }) { access -> cameraWanted(access) }
        catalog = CatalogFlow(link, shell, { screens.recettes.render(it); app.pictures.catalogShown(it.items) }, { screens.fiche.render(it) }, { screens.envoyee.render(it) },
            schedule, clock, notice = { shell.notice(it) }, share = { subject, text -> shareText(subject, text) })
        journal = JournalFlow(link, { screens.journal.render(it) }, clock, open = { id -> catalog.openRecipe(id) })
        appearance = AppearanceFlow(link, app.lookStore) { followLook() }
        robot = RobotFlow(link, app.alertPrefs, { screens.robot.render(it) }, notificationsAllowed = { notificationsAllowed() },
            askNotifications = { askNotifications() }, background = phoneBackground(), clock = clock) { ok, _ ->
            // L'appairage est déjà oublié ici (écran Appairage affiché) ; le robot ne l'a pas confirmé.
            if (!ok) pairing.reset(cameraAccess(), tr("Ce téléphone est oublié ici, mais le robot ne l'a pas confirmé : retirez-le aussi sur le robot."))
        }.also { it.appearance = appearance }
        // Recettes écrites ici : brouillons gardés hors sauvegarde, jusqu'à l'enregistrement par le robot.
        editor = EditFlow(link, shell, { screens.editeur.render(it) }, { screens.etape.render(it) },
            DraftStore(java.io.File(noBackupFilesDir, "brouillons")), schedule, notice = { shell.notice(it) },
            onSaved = { id, leaving -> catalog.recipeSaved(id, leaving) })
        catalog.editor = editor
        // Liste de courses du téléphone (onglet Courses) ; les fiches y ajoutent leurs ingrédients.
        courses = CoursesFlow(load = { app.loadCart() }, save = { app.saveCart(it) }, render = { screens.courses.render(it) },
            share = { subject, text -> shareText(subject, text) }, notice = { shell.notice(it) })
        catalog.addToCart = { courses.addRecipe(it) }
        // Langue (onglet Robot) : l'activité est refaite dans la nouvelle langue.
        robot.language = object : LanguageChoice {
            override fun setting() = CompanionLanguages.setting(this@CompanionActivity)
            override fun options() = CompanionLanguages.options()
            override fun choose(code: String) {
                if (code == setting()) return
                CompanionLanguages.choose(this@CompanionActivity, code)
                recreate()
            }
        }
        // Mises à jour depuis GitHub (onglet Robot, et proposée à l'ouverture de l'appli).
        robot.updates = app.updates
        app.updates.changed = { updateChanged() }
        app.updates.offer = { offerUpdate(it, 0) }
        screens = Screens(this, live, shell, catalog, app.pictures, journal, robot, pairing, editor, courses)
        shell.screens = screens
        live.screens = screens
        live.onOpenSentRecipe = { catalog.showWaiting() }
        live.onWithdraw = { catalog.withdraw() }
        appearance.view = lookView()
        shell.onShown = { route ->
            if (route == Route.RECETTES) catalog.ensureLoaded()
            journal.setVisible(route == Route.JOURNAL)
            if (route == Route.ROBOT) robot.refresh()
            if (route == Route.DIAGNOSTIC) screens.diagnostic.render(DiagnosticState(app.phoneReport(), app.diag.text()))
            // De retour sur un écran calme : la question sur l'apparence du robot, si elle attend.
            appearance.check()
        }
        courses.show()
        shell.setWide(resources.configuration.screenWidthDp >= WIDE_DP)
        pairing.reset(cameraAccess())
        shell.refresh()
        setContentView(shell)
        unsubscribe = link.addListener(this)
        onStatus(link.status)
        // Pas de réseau vers GitHub pendant les tests (Robolectric).
        if (Build.FINGERPRINT != "robolectric") app.updates.start()
    }

    // ------------------------------------------------------------------ mises à jour

    private var lastUpdatePhase = UpdatePhase.IDLE

    /** La mise à jour a avancé : onglet Robot redessiné ; un échec du téléchargement ou de l'installation est dit. */
    private fun updateChanged() {
        robot.refresh()
        val v = app.updates.view
        if (v.phase == UpdatePhase.ERROR && (lastUpdatePhase == UpdatePhase.DOWNLOADING || lastUpdatePhase == UpdatePhase.INSTALLING)) shell.notice(v.error)
        lastUpdatePhase = v.phase
    }

    /**
     * Version plus récente trouvée à l'ouverture : proposée par une feuille, sur un écran calme (pas pendant
     * une saisie, une cuisson suivie ou une autre question) ; essayé chaque seconde pendant deux minutes.
     */
    private fun offerUpdate(v: UpdateView, tries: Int) {
        if (isFinishing || isDestroyed || app.updates.view.phase != UpdatePhase.AVAILABLE) return
        val calm = !shell.sheetShown && (shell.quiet() || shell.current == Route.APPAIRAGE)
        if (!calm) {
            if (tries < 120) main.postDelayed({ offerUpdate(v, tries + 1) }, 1_000)
            return
        }
        val sheet = ConfirmSheet(this, tr("Mise à jour disponible"),
            tr("Toque Compagnon %s est sortie (vous avez la %s). L'installer maintenant ? Android vous demandera de confirmer.", v.version, v.installed),
            tr("Installer"), Ui.Style.PRIMARY, tr("Plus tard")) {
            shell.hideSheet()
            app.updates.install()
            shell.notice(tr("Téléchargement de la mise à jour…"))
        }
        sheet.onCancel = { shell.hideSheet() }
        shell.showSheet(sheet)
        sheet.show()
    }

    /** L'écran Appairage est affiché (pas de robot appairé). */
    private var pairingShown = false

    /** Dernier état de la liaison traité : celui que la liaison redit à l'inscription n'est pas refait. */
    private var handledStatus: LinkStatus? = null

    /** Couleurs avec lesquelles les écrans sont construits ([Ui.lookSeq]). */
    private var lookShown = 0

    /**
     * Apparence du téléphone changée (Apparence, ou celle du robot prise ici) : les écrans sont refaits
     * sur place ([Shell.restyle]), sans recréer l'activité ; ce qui est en cours reste (voir Shell).
     */
    private fun followLook() {
        app.applyLook()
        if (Ui.lookSeq == lookShown) return
        lookShown = Ui.lookSeq
        applyWindow()
        shell.restyle()
        robot.refresh()
    }

    /**
     * Fenêtre aux couleurs du thème : fond (avant le premier dessin), barres d'état et de navigation, et
     * leurs icônes foncées sur le thème clair.
     */
    @Suppress("DEPRECATION")
    private fun applyWindow() {
        val w = window
        // La décoration existe avant setContentView : ses barres se règlent dès le premier dessin.
        w.decorView
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ui.BG))
        w.statusBarColor = Ui.BG
        w.navigationBarColor = Ui.BG
        if (Build.VERSION.SDK_INT >= 30) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (Ui.light) light else 0, light)
        } else {
            var f = w.decorView.systemUiVisibility and
                (android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            if (Ui.light) {
                f = f or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                // Icônes foncées de la barre de navigation : Android 8.1 et plus ; avant, la barre reste foncée.
                if (Build.VERSION.SDK_INT >= 27) f = f or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else w.navigationBarColor = 0xFF000000.toInt()
            }
            w.decorView.systemUiVisibility = f
        }
    }

    /** Les questions de l'apparence, posées sur la coquille. */
    private fun lookView() = object : LookView {
        override fun quiet() = app.visible && shell.quiet()

        override fun offer(o: LookOffer) {
            shell.showSheet(ChoiceSheet.offer(this@CompanionActivity, o) { take, accent, theme ->
                shell.hideSheet()
                if (take) appearance.takeOffer(accent, theme) else appearance.keepMine()
            })
        }

        override fun askRobot(f: RobotFollow) {
            shell.showSheet(ChoiceSheet.follow(this@CompanionActivity, f) { yes ->
                shell.hideSheet()
                appearance.follow(f, yes)
            })
        }

        override fun notice(text: String) = shell.notice(text)

        override fun changed() = robot.refresh()
    }

    override fun onStatus(status: LinkStatus) {
        if (status == handledStatus) return
        handledStatus = status
        when (status.phase) {
            LinkPhase.NOT_PAIRED -> showPairing(null)
            LinkPhase.REMOVED -> showPairing(status.message)
            else -> {
                if (pairingShown && app.link.paired != null) {
                    // Appairage réussi : suivi en arrière-plan et autorisation des notifications, tout de suite.
                    pairingShown = false
                    FollowService.start(this, "appairage")
                    askNotificationsOnce()
                }
                shell.showPairing(false)
            }
        }
    }

    /** L'écran Appairage, en plein écran, avec [message] (téléphone retiré sur le robot…) s'il y en a un. */
    fun showPairing(message: String?) {
        pairingShown = true
        pairing.reset(cameraAccess(), message)
        shell.showPairing(true)
    }

    // ------------------------------------------------------------------ autorisations

    private fun cameraAccess(): CameraAccess = when {
        !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> CameraAccess.UNAVAILABLE
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> CameraAccess.READY
        // Déjà demandée, refusée et plus de dialogue possible : seuls les réglages le peuvent.
        cameraAsked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> CameraAccess.DENIED
        else -> CameraAccess.ASK
    }

    private var cameraAsked: Boolean
        get() = getPreferences(MODE_PRIVATE).getBoolean("camera_asked", false)
        set(v) { getPreferences(MODE_PRIVATE).edit().putBoolean("camera_asked", v).apply() }

    private fun cameraWanted(access: CameraAccess) {
        if (access == CameraAccess.DENIED) openAppSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        else {
            cameraAsked = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    private fun notificationsAllowed(): Boolean =
        AlertPrefs.permitted(this) && getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** Demande l'autorisation des notifications une seule fois, quand un robot est appairé (Android 13+). */
    private fun askNotificationsOnce() {
        val prefs = app.alertPrefs
        if (app.link.paired != null && !prefs.permissionAsked && !AlertPrefs.permitted(this)) {
            prefs.permissionAsked = true
            app.diag.add("autorisation des notifications demandée")
            AlertPrefs.request(this)
        }
    }

    /** Suivi en arrière-plan et limite de batterie, vus de l'écran Robot. */
    private fun phoneBackground() = object : BackgroundFollow {
        override fun enabled() = app.alertPrefs.background
        override fun set(on: Boolean) {
            app.alertPrefs.background = on
            app.diag.add("suivi en arrière-plan : ${if (on) "oui" else "non"}")
            if (on) FollowService.start(this@CompanionActivity, "interrupteur") else FollowService.stop(this@CompanionActivity)
        }
        override fun batteryRestricted() = AlertPrefs.batteryRestricted(this@CompanionActivity)
        override fun liftBattery() = AlertPrefs.requestBattery(this@CompanionActivity)
        override fun alertsMuted() = app.notifier.anyChannelMuted(app.alertPrefs.enabled())
        override fun idleNoticeShown() = !app.notifier.idleHidden()
        override fun openIdleNotice() {
            try {
                startActivity(Notifier.idleSettings(this@CompanionActivity))
            } catch (_: Exception) {
                openAppSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            }
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !AlertPrefs.permitted(this) &&
            (!app.alertPrefs.permissionAsked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            app.alertPrefs.permissionAsked = true
            AlertPrefs.request(this)
        } else openAppSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }

    /** Liste de courses : le sélecteur d'Android (notes, messages, liste de courses…), en texte simple. */
    private fun shareText(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(send, tr("Partager la liste de courses")))
        } catch (_: Exception) {
            shell.notice(tr("Aucune appli pour partager ce texte"))
        }
    }

    private fun openAppSettings(action: String) {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        else i.data = Uri.fromParts("package", packageName, null)
        try {
            startActivity(i)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_REQUEST -> pairing.setCamera(cameraAccess())
            AlertPrefs.PERMISSION_REQUEST -> {
                app.diag.add("notifications ${if (AlertPrefs.permitted(this)) "autorisées" else "refusées"} par l'utilisateur")
                robot.refresh()
            }
        }
    }

    // ------------------------------------------------------------------ cycle de vie

    override fun onStart() {
        super.onStart()
        app.visible = true
        live.attach()
        // À l'écran, Android permet de démarrer le service : le suivi permanent reprend s'il s'était arrêté.
        FollowService.start(this, "appli à l'écran")
    }

    override fun onResume() {
        super.onResume()
        // Retour des réglages : autorisations peut-être changées.
        pairing.setCamera(cameraAccess())
        robot.refresh()
        // Canal « Suivi au repos » peut-être réaffiché.
        FollowService.recheck()
        askNotificationsOnce()
        appearance.check()
    }

    override fun onStop() {
        // Le brouillon en cours est écrit tout de suite : l'appli peut être fermée ensuite.
        editor.flush()
        app.visible = false
        live.detach()
        super.onStop()
    }

    override fun onDestroy() {
        app.updates.changed = {}
        app.updates.offer = {}
        unsubscribe?.invoke()
        shell.close()
        catalog.close()
        editor.close()
        journal.close()
        robot.close()
        appearance.close()
        super.onDestroy()
    }

    @Deprecated("Retour du système (Android 13 et moins ; sans retour prédictif)")
    override fun onBackPressed() {
        if (shell.back()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** Rotation, fenêtre redimensionnée, tablette : l'activité n'est pas refaite, la coquille se réarrange. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        shell.setWide(newConfig.screenWidthDp >= WIDE_DP)
    }

    companion object {
        /** Au moins 600 dp de large : menu sur le côté et deux colonnes pour les recettes. */
        const val WIDE_DP = 600
        private const val CAMERA_REQUEST = 42
    }
}
