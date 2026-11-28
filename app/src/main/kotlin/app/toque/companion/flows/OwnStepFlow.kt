package app.toque.companion.flows

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.OwnStepActions
import app.toque.companion.ui.Route
import app.toque.companion.ui.screens.MonReglageState
import app.toque.core.companion.OwnStepWire
import app.toque.core.companion.OwnStepsWire
import app.toque.core.companion.SettingWire
import app.toque.core.companion.client.CompanionException
import app.toque.core.i18n.tr
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram

/**
 * « Mon réglage » d'une étape d'une recette reçue (Mealie), depuis la fiche, sans copier la recette : le
 * téléphone lit l'étape sur le robot (GET /api/recipes/{id}/steps, avec ce que l'éditeur d'étape du robot
 * propose), on choisit son réglage dans la même feuille que l'écran Étape ([SettingChoice]), et il part aussitôt
 * avec l'empreinte de l'étape lue. Le robot juge (son refus est montré tel quel), refuse une étape dont le texte
 * a changé depuis (l'étape est relue), et le fera vérifier sur place avant son lancement. Pas de brouillon :
 * rien n'est gardé sur le téléphone. Kotlin pur, fil principal.
 */
class OwnStepFlow(
    private val link: RobotLink,
    private val nav: Navigator,
    private val render: (MonReglageState) -> Unit,
    private val notice: (String) -> Unit = {},
    /** Le robot a changé (ou a dit avoir changé) un réglage de la recette [id] : sa fiche est à relire. */
    private val onChanged: (id: String) -> Unit = {},
) : OwnStepActions, RobotLink.Listener {
    var state: MonReglageState? = null
        private set

    private var id = ""
    private var index = -1
    private var title = ""
    /** Empreinte de l'étape sur la fiche touchée : si le robot en a une autre à ce rang, la recette a changé entre-temps. */
    private var expectedKey: String? = null
    private var form: OwnStepsWire? = null
    private var loading = false
    private var error: String? = null
    private var sending = false
    private var refused: String? = null
    private var settingRefused = false
    private var choice: SettingChoice? = null
    private var gen = 0
    private val unsubscribe = link.addListener(this)

    private val online get() = link.status.phase == LinkPhase.CONNECTED

    /** L'étape [index] de la recette [id] (titre [title]), lue avec l'empreinte [key] sur la fiche. */
    fun open(id: String, index: Int, key: String, title: String) {
        gen++
        this.id = id
        this.index = index
        this.title = title
        expectedKey = key
        form = null
        error = null
        sending = false
        refused = null
        settingRefused = false
        choice = null
        load()
        if (nav.current != Route.REGLAGE) nav.open(Route.REGLAGE)
    }

    private fun load() {
        val g = gen
        loading = true
        show()
        link.ownSteps(id) { r ->
            if (g != gen) return@ownSteps
            loading = false
            r.onSuccess { adopt(it) }.onFailure { e ->
                error = message(e)
                show()
            }
        }
    }

    private fun adopt(f: OwnStepsWire) {
        form = f
        error = null
        val st = f.steps.getOrNull(index)
        val expected = expectedKey
        expectedKey = null
        if (st == null) {
            // L'étape n'existe plus sur le robot : rien à régler ici, la fiche se relit.
            error = tr("La recette a changé sur le robot")
            onChanged(id)
        } else if (expected != null && st.key != expected) {
            refused = changedText()
            settingRefused = false
            onChanged(id)
        }
        show()
    }

    private fun changedText() = tr("La recette a changé sur le robot : relisez l'étape, puis choisissez de nouveau son réglage.")

    private fun step(): OwnStepWire? = form?.steps?.getOrNull(index)

    /** Le réglage qui partirait aujourd'hui à cette étape (null : aucun). */
    private fun current(): StepMachine? = SettingWire.of(step()?.machine)

    override fun retryOwnStep() {
        if (id.isEmpty()) return
        gen++
        error = null
        load()
    }

    // ------------------------------------------------------------------ feuille « Réglage du robot »

    override fun openSetting() {
        val f = form ?: return
        if (sending || !online || step() == null) return
        choice = SettingChoice.start(f.settings, current())
        show()
    }

    override fun pickSetting(program: String) {
        val c = choice ?: return
        val f = form ?: return
        choice = c.pick(f.settings, if (program.isEmpty()) null else StepProgram.values().firstOrNull { it.key == program } ?: return)
        show()
    }

    override fun stepSetting(field: String, dir: Int) {
        val c = choice ?: return
        val f = form ?: return
        choice = c.step(f.settings, field, dir)
        show()
    }

    override fun setSettingReverse(reverse: Boolean) {
        choice = (choice ?: return).reverse(reverse)
        show()
    }

    override fun setSettingWhisk(whisk: Boolean) {
        choice = (choice ?: return).whisk(whisk)
        show()
    }

    override fun restoreSetting() = closeSetting()

    override fun closeSetting() {
        if (choice == null) return
        choice = null
        show()
    }

    /** « Utiliser ce réglage » : part aussitôt au robot ; le même réglage que celui de l'étape ne part pas. */
    override fun applySetting() {
        val c = choice ?: return
        val f = form ?: return
        choice = null
        if (c.keep || c.unchanged(f.settings, current())) return show()
        send(c.machine, clear = false)
    }

    /** « Revenir au réglage de la recette ». */
    override fun resetOwnStep() {
        if (step()?.own.isNullOrEmpty()) return
        send(null, clear = true)
    }

    private fun send(machine: StepMachine?, clear: Boolean) {
        val st = step() ?: return
        if (sending) return
        if (!online) {
            refused = tr("Le robot ne répond pas pour l'instant.")
            settingRefused = false
            return show()
        }
        sending = true
        refused = null
        settingRefused = false
        show()
        val g = gen
        val n = index + 1
        link.setOwnStep(id, index, st.key, machine, clear) { r ->
            if (g != gen) return@setOwnStep
            sending = false
            r.onSuccess { f ->
                form = f
                notice(if (clear) tr("Étape %d : réglage de la recette", n) else tr("Réglage envoyé : à vérifier sur le robot"))
                onChanged(id)
                show()
            }.onFailure { refusedBy(it) }
        }
    }

    private fun refusedBy(t: Throwable) {
        val e = t as? CompanionException
        when {
            e == null || e.status == 504 -> {
                refused = tr("Le robot ne répond pas pour l'instant.")
                settingRefused = false
            }
            e.status == 409 && e.error.code == "stale" -> {
                // L'étape n'est plus celle qu'on a lue : rien n'est enregistré, elle se relit (et la fiche aussi).
                refused = changedText()
                settingRefused = false
                onChanged(id)
                gen++
                return load()
            }
            e.status == 422 -> {
                refused = e.error.message.ifBlank { tr("Le robot a refusé la demande") }
                settingRefused = true
            }
            else -> {
                refused = e.error.message.ifBlank { tr("Le robot a refusé la demande") }
                settingRefused = false
            }
        }
        show()
    }

    override fun closeOwnStep() {
        choice = null
        if (nav.current == Route.REGLAGE) nav.back()
    }

    private fun message(t: Throwable): String {
        val e = t as? CompanionException ?: return tr("Le robot ne répond pas pour l'instant.")
        return when (e.status) {
            404 -> tr("Recette introuvable sur le robot")
            409, 422 -> e.error.message.ifBlank { tr("Le robot a refusé la demande") }
            else -> tr("Le robot a refusé la demande")
        }
    }

    // ------------------------------------------------------------------ rendu

    private fun show() {
        if (index < 0) return
        val f = form
        val st = step()
        val c = choice
        val s = MonReglageState(index + 1, f?.title?.ifBlank { null } ?: title, st?.text.orEmpty(), st?.machine?.summary, st?.own.orEmpty(),
            st?.needsReview ?: false, st?.recipe?.summary, loading = loading && f == null, error = if (f == null || st == null) error else null,
            sending = sending, refused = refused, settingRefused = settingRefused, offline = !online,
            sheet = if (f != null && c != null && st != null) SettingChoice.state(index + 1, f.settings, c, current(), st.machine?.summary, canRestore = false) else null)
        state = s
        render(s)
    }

    override fun onStatus(status: LinkStatus) {
        if (!online) choice = null
        if (state != null) show()
    }

    fun close() = unsubscribe()
}
