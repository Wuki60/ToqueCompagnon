package app.toque.companion.flows

import app.toque.companion.alerts.AlertKind
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.Link
import app.toque.companion.ui.RobotActions
import app.toque.companion.ui.screens.PairMethod
import app.toque.companion.ui.screens.RobotState
import app.toque.core.companion.StateWire
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.companion.ui.screens.AlertKind as ScreenAlert

/** Les interrupteurs d'alertes, vus du flux (les préférences du téléphone sur Android). */
interface AlertSwitches {
    fun enabled(): Set<AlertKind>
    fun set(kind: AlertKind, on: Boolean)
}

/** Suivi en arrière-plan et réglages d'Android qui l'empêchent, vus du flux (valeurs fixes dans les tests). */
interface BackgroundFollow {
    /** Interrupteur « Suivi en arrière-plan ». */
    fun enabled(): Boolean = true
    fun set(on: Boolean) {}
    /** Android limite l'appli en arrière-plan (optimisation de la batterie). */
    fun batteryRestricted(): Boolean = false
    /** Ouvrir la demande d'Android pour lever la limite. */
    fun liftBattery() {}
    /** Notifications permises, mais le canal d'une alerte allumée est bloqué ou silencieux dans les réglages d'Android. */
    fun alertsMuted(): Boolean = false
    /** La notification discrète du suivi au repos est affichée (son canal n'est pas coupé dans Android). */
    fun idleNoticeShown(): Boolean = true
    /** Ouvrir la page d'Android de ce canal (la masquer, la réafficher). */
    fun openIdleNotice() {}
}

/**
 * Onglet Robot branché : nom et version du robot (son état), méthode et date d'appairage (gardées
 * à l'appairage), « Oublier » par [RobotLink.forget], interrupteurs d'alertes, et le bandeau quand
 * les notifications du téléphone sont refusées. Kotlin pur, fil principal.
 */
class RobotFlow(
    private val link: RobotLink,
    private val switches: AlertSwitches,
    private val render: (RobotState) -> Unit,
    /** Les notifications de l'appli sont-elles permises par le téléphone ? */
    private val notificationsAllowed: () -> Boolean = { true },
    /** « Autoriser » : demander la permission, ou ouvrir les réglages de notifications de l'appli. */
    private val askNotifications: () -> Unit = {},
    private val background: BackgroundFollow = object : BackgroundFollow {},
    private val clock: PhoneClock = PhoneClock(),
    /** Après « Oublier » (confirmé par le robot ou non) : message éventuel à montrer. */
    private val onForgotten: (ok: Boolean, message: String) -> Unit = { _, _ -> },
) : RobotActions, RobotLink.Listener {
    /** Apparence du téléphone et du robot (section « Apparence ») ; null : section par défaut. */
    var appearance: AppearanceFlow? = null
    /** Mises à jour depuis GitHub (section « Mises à jour ») ; null : section par défaut. */
    var updates: app.toque.companion.update.UpdateFlow? = null
    /** Langue de l'appli (section « Langue ») : réglage, langues proposées, et le changement. */
    var language: LanguageChoice? = null
    private var robot: StateWire? = null
    private var forgetting = false
    var state: RobotState? = null
        private set
    private val unsubscribe = link.addListener(this)

    fun close() = unsubscribe()

    /** Recalcule l'écran (au retour sur l'onglet : autorisation des notifications changée…). */
    fun refresh() {
        val p = link.paired ?: return
        val s = robot
        val l = when (link.status.phase) {
            LinkPhase.CONNECTED -> Link.CONNECTED
            LinkPhase.UNREACHABLE -> Link.OFFLINE
            else -> Link.CONNECTING
        }
        val st = RobotState(
            link = l,
            robotName = s?.robot?.name?.ifBlank { null } ?: p.robotName,
            version = s?.robot?.appVersion.orEmpty(),
            pairMethod = if (p.method == "code") PairMethod.CODE else PairMethod.QR,
            pairedOn = if (p.pairedAt > 0) clock.format("d MMMM", I18n.locale, p.pairedAt) else "",
            alerts = switches.enabled().map { toScreen(it) }.toSet(),
            notificationsAllowed = notificationsAllowed(),
            forgetting = forgetting,
            manualStartOff = manualStartOff(s),
            alertsMuted = background.alertsMuted(),
            background = background.enabled(),
            batteryRestricted = background.batteryRestricted(),
            idleNoticeShown = background.idleNoticeShown(),
            look = appearance?.state() ?: LookState(),
            language = language?.setting() ?: "auto",
            languages = language?.options().orEmpty(),
            update = updates?.view ?: app.toque.companion.update.UpdateView(),
        )
        if (st != state) {
            state = st
            render(st)
        }
    }

    override fun forgetPhone() {
        if (forgetting) return
        forgetting = true
        refresh()
        link.forget { out ->
            forgetting = false
            onForgotten(out.ok, out.message)
        }
    }

    override fun setAlert(kind: ScreenAlert, on: Boolean) {
        switches.set(fromScreen(kind), on)
        refresh()
    }

    override fun allowNotifications() = askNotifications()

    override fun setBackground(on: Boolean) {
        background.set(on)
        refresh()
    }

    override fun allowBackground() = background.liftBattery()

    override fun idleNoticeSettings() = background.openIdleNotice()

    override fun chooseAccent(color: Int) { appearance?.chooseAccent(color) }
    override fun chooseTheme(light: Boolean) { appearance?.chooseTheme(light) }
    override fun takeRobotLook() { appearance?.takeRobot() }
    override fun sendLookToRobot() { appearance?.sendToRobot() }
    override fun chooseLanguage(code: String) { language?.choose(code) }
    override fun checkUpdate() { updates?.check() }
    override fun installUpdate() { updates?.install() }
    override fun retryUpdate() { updates?.retry() }
    override fun setAutoUpdate(on: Boolean) { updates?.setAutoCheck(on) }

    override fun onState(state: StateWire, receivedMono: Long) {
        robot = state
        refresh()
    }

    override fun onStatus(status: LinkStatus) {
        if (status.phase != LinkPhase.CONNECTED) robot = robot.takeIf { status.phase == LinkPhase.UNREACHABLE }
        refresh()
    }

    companion object {
        /** Lancement d'une cuisson manuelle d'ici : null si permis par le robot (même occupé), sinon pourquoi. */
        fun manualStartOff(s: StateWire?): String? {
            val p = s?.can?.start ?: return null
            return when {
                p.code == "disabled" -> tr("Coupé sur le robot")
                !p.allowed && p.code.isEmpty() -> tr("Mettez Toque à jour sur le robot")
                else -> null
            }
        }

        fun toScreen(k: AlertKind) = when (k) {
            AlertKind.WAITING -> ScreenAlert.WAITING
            AlertKind.END -> ScreenAlert.STEP_END
            AlertKind.PAUSE -> ScreenAlert.PAUSE
            AlertKind.OFFLINE -> ScreenAlert.OFFLINE
            AlertKind.TEMP -> ScreenAlert.TEMP
        }

        fun fromScreen(k: ScreenAlert) = when (k) {
            ScreenAlert.WAITING -> AlertKind.WAITING
            ScreenAlert.STEP_END -> AlertKind.END
            ScreenAlert.PAUSE -> AlertKind.PAUSE
            ScreenAlert.OFFLINE -> AlertKind.OFFLINE
            ScreenAlert.TEMP -> AlertKind.TEMP
        }
    }
}

/** Langue de l'appli, vue de l'onglet Robot : réglage (« auto » ou un code), langues (code, nom), changement. */
interface LanguageChoice {
    fun setting(): String
    fun options(): List<Pair<String, String>>
    fun choose(code: String)
}
