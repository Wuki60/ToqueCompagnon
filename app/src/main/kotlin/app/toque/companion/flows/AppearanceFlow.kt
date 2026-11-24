package app.toque.companion.flows

import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.Accent
import app.toque.core.companion.RobotLooks
import app.toque.core.companion.RobotWire
import app.toque.core.companion.StateWire

/** L'apparence choisie sur le téléphone, gardée sur le téléphone (préférences sur Android). */
interface LookStore {
    /** « #RRGGBB » (une des dix couleurs du robot, ou une de la roue), vide : l'orange d'origine. */
    var accent: String
    /** « light » ou « dark » (vide : sombre). */
    var theme: String
    /** Dernière apparence du robot sur laquelle la question a été posée (« id|#RRGGBB|theme »). */
    var asked: String
}

class MemoryLookStore(override var accent: String = "", override var theme: String = "", override var asked: String = "") : LookStore

/** Ce que le robot montre et que le téléphone n'a pas : sa couleur, son thème (null : pas de différence). */
data class LookOffer(val accent: Int?, val light: Boolean?)

/** Ce que la personne vient de changer sur le téléphone, à proposer au robot (null : rien à proposer). */
data class RobotFollow(val accent: Int?, val light: Boolean?)

/** Section « Apparence » de l'onglet Robot. */
data class LookState(
    /** Couleur choisie sur le téléphone. */
    val accent: Int = Accent.DEFAULT,
    val light: Boolean = false,
    /** Apparence du robot, si elle est connue (robot qui la dit). */
    val robotAccent: Int? = null,
    val robotLight: Boolean? = null,
    /** « Prendre l'apparence du robot » : elle diffère de celle du téléphone. */
    val canTake: Boolean = false,
    /** « Appliquer au robot » : le robot le permet et son apparence diffère. */
    val canSend: Boolean = false,
    /** Envoi au robot en cours. */
    val sending: Boolean = false,
)

/** Ce que l'interface fait pour le flux (questions, messages) ; vide dans les tests qui n'en ont pas besoin. */
interface LookView {
    /** Rien ne serait dérangé par une question (écran calme, rien en saisie ni ouvert). */
    fun quiet(): Boolean = true
    /** Poser la question « Le robot a une autre apparence » ; la réponse revient par [AppearanceFlow.takeOffer] ou [AppearanceFlow.keepMine]. */
    fun offer(o: LookOffer) {}
    /** Poser la question « Appliquer aussi au robot ? » ; réponse par [AppearanceFlow.follow]. */
    fun askRobot(f: RobotFollow) {}
    fun notice(text: String) {}
    /** La section « Apparence » est à redessiner. */
    fun changed() {}
}

/**
 * Apparence du téléphone et du robot, chacun la sienne (thème et couleur à part). Le téléphone ne prend
 * jamais celle du robot de lui-même : il le propose une fois par apparence du robot, sur un écran calme
 * ([LookView.quiet]) ; après un choix fait ici, il propose de l'appliquer au robot si celui-ci le permet
 * (`can.setAppearance`). Kotlin pur, fil principal.
 */
class AppearanceFlow(
    private val link: RobotLink,
    private val store: LookStore,
    /** Les couleurs gardées dans [store] ont changé : les appliquer à l'interface. */
    private val apply: () -> Unit,
) : RobotLink.Listener {
    var view: LookView = object : LookView {}
    /** Une question est affichée : pas d'autre en même temps. */
    private var asking = false
    private var sending = false
    private val unsubscribe = link.addListener(this)

    fun close() = unsubscribe()

    // ------------------------------------------------------------------ lecture

    val phoneAccent: Int get() = Accent.parse(store.accent) ?: Accent.DEFAULT
    val phoneLight: Boolean get() = store.theme == RobotWire.THEME_LIGHT

    /** Couleur du robot (null : il ne la dit pas), du dernier état, sinon gardée avec l'appairage. */
    private fun robotAccent(): Int? = Accent.parse(robotLook().first.ifEmpty { null })

    private fun robotLight(): Boolean? = robotLook().second.ifEmpty { null }?.let { it == RobotWire.THEME_LIGHT }

    private fun robotLook(): Pair<String, String> {
        val s = link.latest
        if (s != null) return s.robot.accent to s.robot.theme
        val p = link.paired ?: return "" to ""
        return p.accent to p.theme
    }

    private fun key(): String {
        val (a, t) = robotLook()
        return (link.paired?.robotId ?: "") + "|" + a + "|" + t
    }

    /** Ce qui diffère entre le robot et le téléphone (null : rien, ou robot qui ne dit pas son apparence). */
    fun difference(): LookOffer? {
        if (link.paired == null) return null
        val a = robotAccent()?.takeIf { it != phoneAccent }
        val l = robotLight()?.takeIf { it != phoneLight }
        return if (a == null && l == null) null else LookOffer(a, l)
    }

    private fun canSetRobot(): Boolean = link.latest?.can?.setAppearance?.allowed == true

    /**
     * Le robot prendrait [color] : une des dix partout ; une couleur de la roue seulement d'un robot qui le
     * dit (`robot.anyAccent`). Un robot plus ancien répondrait 400 : on ne la lui propose pas.
     */
    fun robotTakes(color: Int): Boolean = link.latest?.robot?.takesAccent(hexOf(color))
        ?: (hexOf(color) in RobotLooks.ACCENTS)

    fun state(): LookState {
        val d = difference()
        return LookState(phoneAccent, phoneLight, robotAccent(), robotLight(), canTake = d != null,
            canSend = d != null && canSetRobot() && (d.accent == null || robotTakes(phoneAccent)), sending = sending)
    }

    // ------------------------------------------------------------------ la question du robot

    /** Pose la question si le robot a une autre apparence, pas encore proposée, et que rien n'est dérangé. */
    fun check() {
        if (asking) return
        val d = difference() ?: return
        if (key() == store.asked) return
        if (!view.quiet()) return
        asking = true
        view.offer(d)
    }

    /** « Appliquer » : prendre ce qui est coché ([accent], [theme]) de l'apparence du robot. */
    fun takeOffer(accent: Boolean, theme: Boolean) {
        asking = false
        store.asked = key()
        val d = difference()
        if (d != null) {
            if (accent && d.accent != null) store.accent = hexOf(d.accent)
            if (theme && d.light != null) store.theme = themeOf(d.light)
            if ((accent && d.accent != null) || (theme && d.light != null)) apply()
        }
        view.changed()
    }

    /** « Garder le mien » (ou retour) : plus de question avant que l'apparence du robot change encore. */
    fun keepMine() {
        asking = false
        store.asked = key()
        view.changed()
    }

    // ------------------------------------------------------------------ choisi sur le téléphone

    /**
     * Couleur choisie dans « Apparence » (une des dix ou une de la roue) ; puis, si le robot le permet,
     * diffère et sait prendre cette couleur ([robotTakes]), « Appliquer aussi au robot ? ».
     */
    fun chooseAccent(color: Int) {
        val c = color or (0xFF shl 24)
        if (c == phoneAccent) return
        store.accent = if (c == Accent.DEFAULT) "" else hexOf(c)
        changedHere()
        if (canSetRobot() && robotAccent() != null && robotAccent() != c && robotTakes(c)) ask(RobotFollow(c, null))
    }

    /** Thème choisi dans « Apparence » ; même proposition pour le robot. */
    fun chooseTheme(light: Boolean) {
        if (light == phoneLight) return
        store.theme = themeOf(light)
        changedHere()
        if (canSetRobot() && robotLight() != null && robotLight() != light) ask(RobotFollow(null, light))
    }

    private fun changedHere() {
        // Choisi en connaissance de cause : l'apparence actuelle du robot n'est plus proposée.
        if (link.paired != null) store.asked = key()
        apply()
        view.changed()
    }

    private fun ask(f: RobotFollow) {
        if (asking) return
        asking = true
        view.askRobot(f)
    }

    /** Réponse à « Appliquer aussi au robot ? ». */
    fun follow(f: RobotFollow, yes: Boolean) {
        asking = false
        if (yes) send(f.accent, f.light)
    }

    /** « Prendre l'apparence du robot » (section Apparence) : tout ce qui diffère. */
    fun takeRobot() {
        val d = difference() ?: return
        takeOffer(d.accent != null, d.light != null)
    }

    /** « Appliquer au robot » (section Apparence) : ce qui diffère. */
    fun sendToRobot() {
        val d = difference() ?: return
        if (d.accent != null && !robotTakes(phoneAccent)) return
        send(if (d.accent != null) phoneAccent else null, if (d.light != null) phoneLight else null)
    }

    private fun send(accent: Int?, light: Boolean?) {
        if (sending || (accent == null && light == null)) return
        sending = true
        view.changed()
        link.setAppearance(accent?.let { hexOf(it) }, light?.let { themeOf(it) }) { out ->
            sending = false
            if (!out.ok) view.notice(out.message)
            else store.asked = key()
            view.changed()
        }
    }

    override fun onState(state: StateWire, receivedMono: Long) {
        view.changed()
        check()
    }

    override fun onStatus(status: LinkStatus) {
        view.changed()
    }

    companion object {
        fun hexOf(c: Int): String = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)
        fun themeOf(light: Boolean): String = if (light) RobotWire.THEME_LIGHT else RobotWire.THEME_DARK
        /** Les dix couleurs proposées (celles du robot). */
        val COLOURS: List<Int> = RobotLooks.ACCENTS.map { Accent.parse(it)!! }
    }
}
