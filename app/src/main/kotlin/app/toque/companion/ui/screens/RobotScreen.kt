package app.toque.companion.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.flows.AppearanceFlow
import app.toque.companion.flows.LookState
import app.toque.companion.ui.ChoiceSheet
import app.toque.companion.ui.ColourWheelView
import app.toque.companion.ui.ConfirmSheet
import app.toque.companion.ui.FlowRow
import app.toque.companion.ui.Swatch
import app.toque.companion.ui.Icon
import app.toque.companion.ui.Link
import app.toque.companion.ui.LiveHeader
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.RobotActions
import app.toque.companion.ui.Route
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.WheelSheet
import app.toque.companion.ui.settingRow
import app.toque.companion.update.UpdatePhase
import app.toque.companion.update.UpdateView
import app.toque.core.i18n.tr

/** Alertes du téléphone (réglages du téléphone, pas du robot). */
enum class AlertKind { WAITING, STEP_END, PAUSE, OFFLINE, TEMP }

/** Comment ce téléphone a été appairé. */
enum class PairMethod { QR, CODE }

/** Le robot, l'appairage et les autorisations (racine de l'onglet Robot, maquette « Robot et autorisations »). */
data class RobotState(
    val link: Link = Link.CONNECTED,
    /** Nom du robot : « Monsieur Cuisine Connect ». */
    val robotName: String,
    /** Version de Toque sur le robot : « 0.7.0 ». */
    val version: String,
    val pairMethod: PairMethod,
    /** Date d'appairage, déjà formatée : « 21 novembre ». */
    val pairedOn: String,
    /** Alertes allumées. */
    val alerts: Set<AlertKind> = emptySet(),
    /** false : les notifications de l'appli sont refusées par le téléphone (les alertes ne sonneront pas). */
    val notificationsAllowed: Boolean = true,
    /** « Oublier » en cours. */
    val forgetting: Boolean = false,
    /** Lancer une cuisson manuelle d'ici : null = oui, sinon pourquoi non (« Coupé sur le robot »). */
    val manualStartOff: String? = null,
    /** Notifications permises, mais le canal d'une alerte allumée est bloqué ou silencieux dans Android. */
    val alertsMuted: Boolean = false,
    /** « Suivi en arrière-plan » : Oui. */
    val background: Boolean = true,
    /** Le téléphone limite l'appli en arrière-plan (batterie). */
    val batteryRestricted: Boolean = false,
    /** La notification discrète du suivi au repos est affichée (pas masquée dans les réglages d'Android). */
    val idleNoticeShown: Boolean = true,
    /** Section « Apparence » (apparence du téléphone, celle du robot). */
    val look: LookState = LookState(),
    /** Section « Langue » : « auto » ou un code (fr, en…), et les langues proposées (code, nom). */
    val language: String = "auto",
    val languages: List<Pair<String, String>> = emptyList(),
    /** Section « Mises à jour » (versions publiées sur GitHub). */
    val update: UpdateView = UpdateView(),
)

class RobotScreen(c: Context, private val actions: RobotActions, private val nav: Navigator) : Screen<RobotState>(c) {
    override val tab = Tab.ROBOT
    private val header = LiveHeader(c, tr("Robot"))
    private val name = Ui.ellipsize(Ui.text(c, "", 16f, Ui.TEXT, Ui.MEDIUM))
    private val version = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2), 2)
    private val paired = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2), 2)
    private lateinit var forget: Ui.Button
    private val blocked = Ui.horizontal(c)
    private lateinit var blockedText: TextView
    private lateinit var blockedButton: Ui.Button
    private val battery = Ui.horizontal(c)
    /** Langue : une pastille par langue, « Automatique » d'abord (refaites quand la liste change). */
    private val languageChips = FlowRow(c, hGap = 8, vGap = 8)
    private var languageCodes: List<Pair<String, String>> = emptyList()
    private val updateTitle = Ui.text(c, "", 15f, lineHeight = 20f)
    private val updateStatus = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)
    private lateinit var updateButton: Ui.Button
    private lateinit var autoUpdateSwitch: TextView
    private lateinit var backgroundSwitch: TextView
    private lateinit var idleRow: View
    private lateinit var idleDivider: View
    private val idleDetail = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)
    private val switches = HashMap<AlertKind, TextView>()
    private lateinit var confirm: ConfirmSheet
    private lateinit var dark: TextView
    private lateinit var lightChip: TextView
    private val swatches = ArrayList<Swatch>()
    /** Couleur prise sur la roue (hors des dix), cochée devant le bouton de la roue. */
    private val customSwatch = Swatch(c, Ui.ACCENT)
    private lateinit var wheelSheet: WheelSheet
    private var lookAccent = Ui.ACCENT
    private val robotLook = Ui.vertical(c)
    private val robotSwatch = Swatch(c, Ui.ACCENT, 24)
    private val robotLookText = Ui.ellipsize(Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 19f), 2)
    private lateinit var takeLook: Ui.Button
    private lateinit var sendLook: Ui.Button
    private val manualDetail = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)
    private lateinit var manualYes: View
    private val manualNo = Ui.text(c, tr("Non"), 14f, Ui.TEXT2, Ui.MEDIUM)

    override fun build(): View {
        val column = Ui.vertical(c)

        // Le robot et ce téléphone.
        val robot = Ui.card(c, 24f)
        Ui.pad(robot, 16, 16, 16, 16)
        val top = Ui.horizontal(c)
        val badge = FrameLayout(c)
        // Comme les pastilles de l'accueil du robot : rond plein de sa couleur, icône foncée dessus.
        badge.background = Ui.round(Ui.ACCENT, 22f)
        badge.addView(Icon(c, Icon.Kind.POT, Ui.ON_ACCENT), Ui.flp(Ui.dp(24), Ui.dp(24), Gravity.CENTER))
        top.addView(badge, LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)).apply { rightMargin = Ui.dp(12) })
        val names = Ui.vertical(c)
        names.addView(name)
        names.addView(version, Ui.lp(t = 2))
        top.addView(names, Ui.lp(0, Ui.WRAP, 1f))
        robot.addView(top)
        robot.addView(Ui.divider(c), Ui.lp(Ui.MATCH, maxOf(1, Ui.dp(1)), t = 12))
        val phone = Ui.horizontal(c)
        val qr = FrameLayout(c)
        qr.addView(Icon(c, Icon.Kind.QR, Ui.OK), Ui.flp(Ui.dp(22), Ui.dp(22), Gravity.CENTER))
        phone.addView(qr, LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)).apply { rightMargin = Ui.dp(12) })
        val phoneText = Ui.vertical(c)
        phoneText.addView(Ui.ellipsize(Ui.text(c, tr("Téléphone appairé"), 15f)))
        phoneText.addView(paired, Ui.lp(t = 1))
        phone.addView(phoneText, Ui.lp(0, Ui.WRAP, 1f, r = 8))
        forget = Ui.button(c, tr("Oublier"), Ui.Style.GHOST, height = 44, textSize = 14f) { if (state?.forgetting == false) confirm.show() }
        forget.setPadding(Ui.dp(14), 0, Ui.dp(14), 0)
        phone.addView(forget, Ui.lp(Ui.WRAP, Ui.WRAP))
        robot.addView(phone, Ui.lp(t = 2))
        column.addView(robot, Ui.lp(l = 20, t = 12, r = 20))

        // Autorisations fixées par Toque : affichées, sans réglage.
        column.addView(Ui.overline(c, tr("Depuis ce téléphone")), Ui.lp(l = 20, t = 20, r = 20))
        column.addView(Ui.text(c, tr("Ces règles sont fixées par Toque, elles ne se règlent pas ici."), 13f, Ui.TEXT2, lineHeight = 18f),
            Ui.lp(l = 20, t = 4, r = 20))
        val rules = Ui.card(c)
        Ui.pad(rules, 16, 0, 16, 0)
        listOf(
            tr("Mettre en pause, arrêter") to null,
            tr("Reprendre après une pause") to tr("Si le couvercle est resté fermé"),
            tr("Ajuster les réglages") to tr("Durée, température, vitesse et sens"),
            tr("Remettre la balance à zéro") to null,
            tr("Envoyer une recette") to tr("Elle attend d'être lancée sur le robot"),
            tr("Changer le nombre de personnes") to null,
        ).forEachIndexed { i, (t, d) ->
            if (i > 0) rules.addView(Ui.divider(c))
            rules.addView(settingRow(c, t, d, allowed(), if (d == null) 52 else 60))
        }
        // Lancement d'une cuisson manuelle : réglé sur le robot (Réglages › Téléphone › Lancer depuis le téléphone).
        rules.addView(Ui.divider(c))
        val manualRow = Ui.horizontal(c)
        manualRow.minimumHeight = Ui.dp(60)
        val manualCol = Ui.vertical(c)
        manualCol.addView(Ui.text(c, tr("Lancer une cuisson manuelle"), 15f, lineHeight = 20f))
        manualCol.addView(manualDetail, Ui.lp(t = 1))
        manualRow.addView(manualCol, Ui.lp(0, Ui.WRAP, 1f, t = 8, r = 12, b = 8))
        manualYes = allowed()
        manualRow.addView(manualYes)
        manualRow.addView(manualNo)
        rules.addView(manualRow)
        column.addView(rules, Ui.lp(l = 20, t = 10, r = 20))

        // Ce qui ne se fait que sur le robot.
        column.addView(Ui.overline(c, tr("Toujours sur le robot")), Ui.lp(l = 20, t = 20, r = 20))
        val only = Ui.horizontal(c, Gravity.TOP)
        only.background = Ui.round(Ui.SURFACE, 20f)
        Ui.pad(only, 16, 14, 16, 14)
        only.addView(Icon(c, Icon.Kind.SCREEN, Ui.TEXT2), LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)).apply { rightMargin = Ui.dp(12); topMargin = Ui.dp(1) })
        val onlyList = Ui.vertical(c)
        listOf(
            tr("Lancer une recette ou un programme"),
            tr("Lancer l'étape suivante d'une recette"),
            tr("Reprendre après ouverture du couvercle"),
            tr("Turbo et mixage à chaud"),
        ).forEachIndexed { i, t -> onlyList.addView(Ui.text(c, t, 14f, lineHeight = 20f), Ui.lp(t = if (i > 0) 6 else 0)) }
        only.addView(onlyList, Ui.lp(0, Ui.WRAP, 1f))
        column.addView(only, Ui.lp(l = 20, t = 10, r = 20))

        // Alertes : de vrais interrupteurs (réglages du téléphone).
        column.addView(Ui.overline(c, tr("Alertes")), Ui.lp(l = 20, t = 20, r = 20))
        blocked.background = Ui.round(Ui.WARN_SOFT, 20f, Ui.WARN_LINE, 1f)
        Ui.pad(blocked, 16, 10, 8, 10)
        blocked.addView(Icon(c, Icon.Kind.BELL, Ui.WARN), LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)).apply { rightMargin = Ui.dp(12) })
        blockedText = Ui.text(c, tr("Les notifications de Toque Compagnon sont désactivées sur ce téléphone."), 13f, Ui.TEXT, lineHeight = 18f)
        blocked.addView(blockedText, Ui.lp(0, Ui.WRAP, 1f, r = 8))
        blockedButton = Ui.button(c, tr("Autoriser"), Ui.Style.SECONDARY, height = 40, textSize = 14f) { actions.allowNotifications() }
            .apply { setPadding(Ui.dp(14), 0, Ui.dp(14), 0) }
        blocked.addView(blockedButton, Ui.lp(Ui.WRAP, Ui.WRAP))
        column.addView(blocked, Ui.lp(l = 20, t = 10, r = 20))
        val alerts = Ui.card(c)
        Ui.pad(alerts, 16, 0, 12, 0)
        listOf(
            Triple(AlertKind.WAITING, tr("Le robot vous attend") to tr("Pesée, ajout d'un ingrédient"), tr("Alerte quand le robot vous attend")),
            Triple(AlertKind.STEP_END, tr("Fin d'étape, fin de cuisson") to null, tr("Alerte en fin d'étape et de cuisson")),
            Triple(AlertKind.TEMP, tr("Température atteinte") to tr("Cuisson manuelle ou étape de recette"), tr("Alerte quand la température est atteinte")),
            Triple(AlertKind.PAUSE, tr("Pause, couvercle, bol retiré") to null, tr("Alerte en cas de pause")),
            Triple(AlertKind.OFFLINE, tr("Robot hors ligne") to null, tr("Alerte quand le robot est hors ligne")),
        ).forEachIndexed { i, (kind, texts, description) ->
            if (i > 0) alerts.addView(Ui.divider(c))
            val sw = Ui.switch(c, false, description) { on -> actions.setAlert(kind, on) }
            switches[kind] = sw
            alerts.addView(settingRow(c, texts.first, texts.second, sw, if (texts.second == null) 56 else 60))
        }
        column.addView(alerts, Ui.lp(l = 20, t = 10, r = 20))

        // Suivi permanent (service) : les alertes arrivent appli fermée.
        val follow = Ui.card(c)
        Ui.pad(follow, 16, 0, 12, 0)
        backgroundSwitch = Ui.switch(c, true, tr("Suivi en arrière-plan")) { on -> actions.setBackground(on) }
        follow.addView(settingRow(c, tr("Suivi en arrière-plan"),
            tr("Pour recevoir les alertes quand l'appli est fermée. Android exige alors une notification : celle de la cuisson, ou une discrète au repos, que vous pouvez masquer."),
            backgroundSwitch, 60))
        // Notification du repos : affichée ou masquée ; la page de son canal dans Android.
        idleDivider = Ui.divider(c)
        follow.addView(idleDivider)
        val idle = Ui.horizontal(c)
        idle.minimumHeight = Ui.dp(56)
        idle.isClickable = true
        idle.setOnClickListener { actions.idleNoticeSettings() }
        val idleCol = Ui.vertical(c)
        idleCol.addView(Ui.text(c, tr("Notification au repos"), 15f, lineHeight = 20f))
        idleCol.addView(idleDetail, Ui.lp(t = 1))
        idle.addView(idleCol, Ui.lp(0, Ui.WRAP, 1f, t = 8, r = 12, b = 8))
        idle.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT3), LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)))
        idleRow = idle
        follow.addView(idle)
        column.addView(follow, Ui.lp(l = 20, t = 10, r = 20))
        battery.background = Ui.round(Ui.WARN_SOFT, 20f, Ui.WARN_LINE, 1f)
        Ui.pad(battery, 16, 10, 8, 10)
        battery.addView(Icon(c, Icon.Kind.BOLT, Ui.WARN), LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)).apply { rightMargin = Ui.dp(12) })
        battery.addView(Ui.text(c, tr("Ce téléphone limite l'appli pour économiser la batterie : certains téléphones arrêtent alors le suivi, et les alertes n'arrivent plus."),
            13f, Ui.TEXT, lineHeight = 18f), Ui.lp(0, Ui.WRAP, 1f, r = 8))
        battery.addView(Ui.button(c, tr("Autoriser"), Ui.Style.SECONDARY, height = 40, textSize = 14f) { actions.allowBackground() }
            .apply { setPadding(Ui.dp(14), 0, Ui.dp(14), 0) }, Ui.lp(Ui.WRAP, Ui.WRAP))
        column.addView(battery, Ui.lp(l = 20, t = 10, r = 20))

        // Apparence de ce téléphone : la sienne, à part de celle du robot.
        column.addView(Ui.overline(c, tr("Apparence")), Ui.lp(l = 20, t = 20, r = 20))
        column.addView(Ui.text(c, tr("Celle de ce téléphone. Le robot garde la sienne."), 13f, Ui.TEXT2, lineHeight = 18f),
            Ui.lp(l = 20, t = 4, r = 20))
        val look = Ui.card(c)
        Ui.pad(look, 16, 12, 16, 14)
        look.addView(Ui.text(c, tr("Thème"), 15f, lineHeight = 20f))
        val themes = FlowRow(c, hGap = 8, vGap = 8)
        dark = Ui.toggleChip(c, tr("Sombre"), true) { actions.chooseTheme(false) }
        lightChip = Ui.toggleChip(c, tr("Clair"), false) { actions.chooseTheme(true) }
        themes.addView(dark)
        themes.addView(lightChip)
        look.addView(themes, Ui.lp(t = 6))
        look.addView(Ui.text(c, tr("Couleur"), 15f, lineHeight = 20f), Ui.lp(t = 12))
        val colours = FlowRow(c, hGap = 2, vGap = 2)
        AppearanceFlow.COLOURS.forEachIndexed { i, color ->
            val sw = Swatch(c, color)
            sw.contentDescription = tr("Couleur %d", i + 1)
            sw.isClickable = true
            sw.isFocusable = true
            sw.background = Ui.pressable(Ui.round(0, 24f), 24f)
            sw.setOnClickListener { actions.chooseAccent(color) }
            sw.layoutParams = android.view.ViewGroup.LayoutParams(Ui.dp(Ui.TOUCH), Ui.dp(Ui.TOUCH))
            swatches += sw
            colours.addView(sw)
        }
        look.addView(colours, Ui.lp(t = 4))
        // Toute autre couleur : la roue, la même que sur le robot.
        val wheelRow = Ui.horizontal(c)
        customSwatch.contentDescription = tr("Couleur choisie sur la roue")
        customSwatch.chosen = true
        customSwatch.isClickable = true
        customSwatch.background = Ui.pressable(Ui.round(0, 24f), 24f)
        customSwatch.setOnClickListener { wheelSheet.show(lookAccent) }
        wheelRow.addView(customSwatch, LinearLayout.LayoutParams(Ui.dp(Ui.TOUCH), Ui.dp(Ui.TOUCH)).apply { rightMargin = Ui.dp(4) })
        wheelRow.addView(Ui.button(c, tr("Roue des couleurs"), Ui.Style.OUTLINE, height = 44, textSize = 14f) { wheelSheet.show(lookAccent) }
            .also { Ui.ellipsize(it.label, 2) }, Ui.lp(Ui.WRAP, Ui.WRAP))
        look.addView(wheelRow, Ui.lp(t = 4))
        ColourWheelView.Ring.warm()
        // L'apparence du robot, quand elle diffère : la prendre ici, ou y mettre celle du téléphone.
        robotLook.addView(Ui.divider(c), Ui.lp(Ui.MATCH, maxOf(1, Ui.dp(1)), t = 10))
        val robotRow = Ui.horizontal(c)
        robotRow.minimumHeight = Ui.dp(44)
        robotSwatch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        robotRow.addView(robotSwatch, LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)).apply { rightMargin = Ui.dp(10) })
        robotRow.addView(robotLookText, Ui.lp(0, Ui.WRAP, 1f))
        robotLook.addView(robotRow, Ui.lp(t = 6))
        val lookButtons = FlowRow(c, hGap = 8, vGap = 8)
        takeLook = Ui.button(c, tr("Prendre celle du robot"), Ui.Style.OUTLINE, height = 44, textSize = 14f) { actions.takeRobotLook() }
            .also { Ui.ellipsize(it.label, 2) }
        sendLook = Ui.button(c, tr("Appliquer au robot"), Ui.Style.OUTLINE, height = 44, textSize = 14f) { actions.sendLookToRobot() }
            .also { Ui.ellipsize(it.label, 2) }
        lookButtons.addView(takeLook)
        lookButtons.addView(sendLook)
        robotLook.addView(lookButtons, Ui.lp(t = 4))
        look.addView(robotLook)
        column.addView(look, Ui.lp(l = 20, t = 10, r = 20))

        // Langue de l'appli (comme « Langue · Language » sur le robot) ; par défaut, celle du téléphone.
        column.addView(Ui.overline(c, tr("Langue") + " · Language"), Ui.lp(l = 20, t = 20, r = 20))
        val lang = Ui.card(c)
        Ui.pad(lang, 16, 12, 16, 14)
        lang.addView(languageChips)
        column.addView(lang, Ui.lp(l = 20, t = 10, r = 20))

        // Mises à jour : les versions publiées sur GitHub.
        column.addView(Ui.overline(c, tr("Mises à jour")), Ui.lp(l = 20, t = 20, r = 20))
        val upd = Ui.card(c)
        Ui.pad(upd, 16, 12, 16, 8)
        upd.addView(updateTitle)
        upd.addView(updateStatus, Ui.lp(t = 4))
        updateButton = Ui.button(c, " ", Ui.Style.OUTLINE, Icon.Kind.DOWNLOAD, height = 44, textSize = 14f, iconSize = 18) {
            val u = state?.update ?: return@button
            when (u.phase) {
                UpdatePhase.AVAILABLE -> actions.installUpdate()
                UpdatePhase.ERROR -> actions.retryUpdate()
                UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING -> {}
                else -> actions.checkUpdate()
            }
        }
        Ui.ellipsize(updateButton.label, 2)
        upd.addView(updateButton, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        upd.addView(Ui.divider(c), Ui.lp(Ui.MATCH, maxOf(1, Ui.dp(1)), t = 8))
        autoUpdateSwitch = Ui.switch(c, true, tr("Vérifier au démarrage")) { on -> actions.setAutoUpdate(on) }
        upd.addView(settingRow(c, tr("Vérifier au démarrage"), tr("À l'ouverture de l'appli, sur GitHub"), autoUpdateSwitch, 60))
        column.addView(upd, Ui.lp(l = 20, t = 10, r = 20))

        // Diagnostic, discret, tout en bas.
        val diag = Ui.horizontal(c)
        diag.minimumHeight = Ui.dp(Ui.TOUCH)
        diag.isClickable = true
        diag.setOnClickListener { nav.open(Route.DIAGNOSTIC) }
        diag.addView(Ui.ellipsize(Ui.text(c, tr("Diagnostic des alertes"), 14f, Ui.TEXT2)), Ui.lp(0, Ui.WRAP, 1f))
        diag.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT3), LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)))
        column.addView(diag, Ui.lp(l = 20, t = 14, r = 20))
        column.addView(Ui.spacer(c, h = 12))

        val root = FrameLayout(c)
        val page = Ui.vertical(c)
        // En-tête fixé en haut, comme sur les autres onglets.
        page.addView(header, Ui.lp())
        page.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))
        root.addView(page, Ui.flp())
        confirm = ConfirmSheet(c, tr("Oublier ce téléphone ?"),
            tr("Il ne pourra plus suivre ni piloter le robot. Pour le reprendre, il faudra l'appairer de nouveau devant le robot."),
            tr("Oublier")) { actions.forgetPhone() }
        root.addView(confirm, Ui.flp())
        wheelSheet = WheelSheet(c) { actions.chooseAccent(it) }
        root.addView(wheelSheet, Ui.flp())
        return root
    }

    /** « ✓ Oui » vert d'une autorisation fixe. */
    private fun allowed(): View {
        val v = Ui.horizontal(c)
        v.addView(Icon(c, Icon.Kind.CHECK, Ui.OK), LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)).apply { rightMargin = Ui.dp(6) })
        v.addView(Ui.text(c, tr("Oui"), 14f, Ui.OK, Ui.MEDIUM))
        return v
    }

    override fun bind(s: RobotState, old: RobotState?) {
        header.set(s.link)
        Ui.set(name, s.robotName)
        Ui.set(version, tr("Toque %s · Wi-Fi de la maison", s.version))
        Ui.set(paired, when (s.pairMethod) {
            PairMethod.QR -> tr("Par QR code, le %s", s.pairedOn)
            PairMethod.CODE -> tr("Par code, le %s", s.pairedOn)
        })
        Ui.setButtonEnabled(forget, !s.forgetting)
        Ui.set(forget.label, if (s.forgetting) tr("Oubli…") else tr("Oublier"))
        Ui.show(blocked, !s.notificationsAllowed || s.alertsMuted)
        Ui.set(blockedText, if (!s.notificationsAllowed) tr("Les notifications de Toque Compagnon sont désactivées sur ce téléphone.")
            else tr("Certaines alertes sont coupées ou silencieuses dans les réglages d'Android."))
        Ui.set(blockedButton.label, if (!s.notificationsAllowed) tr("Autoriser") else tr("Réglages"))
        Ui.setSwitch(backgroundSwitch, s.background)
        Ui.show(battery, s.background && s.batteryRestricted)
        Ui.show(idleRow, s.background)
        Ui.show(idleDivider, s.background)
        Ui.set(idleDetail, if (s.idleNoticeShown) tr("Affichée quand rien n'est en cours") else tr("Masquée dans les réglages d'Android"))
        for ((kind, sw) in switches) Ui.setSwitch(sw, kind in s.alerts)
        val off = s.manualStartOff
        Ui.set(manualDetail, off ?: tr("Couvercle fermé, bol en place"))
        Ui.show(manualYes, off == null)
        Ui.show(manualNo, off != null)
        bindLook(s.look)
        bindLanguage(s)
        bindUpdate(s.update)
    }

    private fun bindLanguage(s: RobotState) {
        val options = listOf("auto" to tr("Automatique")) + s.languages
        if (options != languageCodes) {
            languageCodes = options
            languageChips.removeAllViews()
            for ((code, label) in options) {
                languageChips.addView(Ui.toggleChip(c, label, code == s.language) { actions.chooseLanguage(code) }.apply { tag = code })
            }
        }
        for (i in 0 until languageChips.childCount) {
            val t = languageChips.getChildAt(i) as TextView
            val on = t.tag == s.language
            if (t.isSelected != on) Ui.setToggle(t, on)
        }
    }

    private fun bindUpdate(u: UpdateView) {
        Ui.set(updateTitle, tr("Toque Compagnon %s", u.installed))
        Ui.set(updateStatus, when (u.phase) {
            UpdatePhase.IDLE -> tr("Les nouvelles versions sont publiées sur GitHub.")
            UpdatePhase.CHECKING -> tr("Recherche sur GitHub…")
            UpdatePhase.UP_TO_DATE -> tr("C'est la dernière version.")
            UpdatePhase.AVAILABLE -> tr("La version %s est disponible.", u.version)
            UpdatePhase.DOWNLOADING -> tr("Téléchargement de la version %s : %d %%", u.version, u.percent)
            UpdatePhase.INSTALLING -> tr("Confirmez l'installation dans la fenêtre d'Android.")
            UpdatePhase.ERROR -> u.error
        })
        Ui.setColor(updateStatus, if (u.phase == UpdatePhase.ERROR) Ui.WARN else Ui.TEXT2)
        val (label, style) = when (u.phase) {
            UpdatePhase.AVAILABLE -> tr("Installer la version %s", u.version) to Ui.Style.PRIMARY
            UpdatePhase.ERROR -> tr("Réessayer") to Ui.Style.OUTLINE
            UpdatePhase.CHECKING -> tr("Recherche…") to Ui.Style.OUTLINE
            UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING -> tr("Installation…") to Ui.Style.OUTLINE
            else -> tr("Rechercher une mise à jour") to Ui.Style.OUTLINE
        }
        Ui.set(updateButton.label, label)
        updateButton.setStyle(style)
        Ui.setButtonEnabled(updateButton, u.phase != UpdatePhase.CHECKING && u.phase != UpdatePhase.DOWNLOADING && u.phase != UpdatePhase.INSTALLING)
        Ui.setSwitch(autoUpdateSwitch, u.autoCheck)
    }

    private fun bindLook(l: LookState) {
        if (dark.isSelected == l.light) Ui.setToggle(dark, !l.light)
        if (lightChip.isSelected != l.light) Ui.setToggle(lightChip, l.light)
        for (sw in swatches) sw.chosen = sw.color == l.accent
        lookAccent = l.accent
        val custom = swatches.none { it.color == l.accent }
        Ui.show(customSwatch, custom)
        if (custom) customSwatch.color = l.accent
        val ra = l.robotAccent
        val rl = l.robotLight
        Ui.show(robotLook, l.canTake && (ra != null || rl != null))
        if (ra != null) robotSwatch.color = ra
        Ui.show(robotSwatch, ra != null)
        Ui.set(robotLookText, when {
            ra != null && rl != null -> tr("Sur le robot : cette couleur, %s", (if (rl) tr("mode clair") else tr("mode sombre")))
            ra != null -> tr("Sur le robot : cette couleur")
            rl != null -> tr("Sur le robot : %s", (if (rl) tr("mode clair") else tr("mode sombre")))
            else -> ""
        })
        Ui.show(sendLook, l.canSend)
        Ui.setButtonEnabled(sendLook, !l.sending)
        Ui.set(sendLook.label, if (l.sending) tr("Envoi…") else tr("Appliquer au robot"))
    }

    override fun onBack(): Boolean {
        if (wheelSheet.showing) {
            wheelSheet.hide()
            return true
        }
        if (!confirm.showing) return false
        confirm.hide()
        return true
    }

    override fun onHide() {
        confirm.hide()
        wheelSheet.hide()
    }

    /** La roue (essais) : ouverte sur la couleur du téléphone. */
    fun openWheel(): WheelSheet {
        wheelSheet.show(lookAccent)
        return wheelSheet
    }
}
