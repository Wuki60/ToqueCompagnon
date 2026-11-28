package app.toque.companion.flows

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.EditActions
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Route
import app.toque.companion.ui.screens.EditBanner
import app.toque.companion.ui.screens.EditLine
import app.toque.companion.ui.screens.EditPhase
import app.toque.companion.ui.screens.EditStepRow
import app.toque.companion.ui.screens.EditeurState
import app.toque.companion.ui.screens.EtapeState
import app.toque.core.companion.EditLimitsWire
import app.toque.core.companion.EditableRecipe
import app.toque.core.companion.SettingEdit
import app.toque.core.companion.StepSettingsWire
import app.toque.core.companion.client.CompanionException
import app.toque.core.recipes.StepProgram
import app.toque.core.i18n.tr
import app.toque.core.json.Json
import java.security.SecureRandom

/**
 * Écrire une recette perso sur le téléphone (Éditeur, Étape) : le téléphone sert de clavier, le robot
 * garde la recette. Chaque changement est gardé en brouillon ([DraftStore]) jusqu'à ce que le robot ait
 * enregistré ; le brouillon est proposé de nouveau à la réouverture. Le robot juge à l'enregistrement :
 * son refus montre le champ ou ouvre l'étape concernée, avec son message. Chez un robot qui le dit
 * ([StepSettingsWire], lu avec la recette ou par GET /api/editor pour une recette nouvelle), le réglage du
 * robot d'une étape se choisit dans une feuille ([SettingChoice]) avec les bornes que le robot annonce ; il
 * part seulement s'il a été touché ici, et le robot le juge et le fera vérifier sur place avant son premier
 * lancement. Kotlin pur, fil principal.
 */
class EditFlow(
    private val link: RobotLink,
    private val nav: Navigator,
    private val render: (EditeurState) -> Unit,
    private val renderStep: (EtapeState) -> Unit,
    private val drafts: DraftStore,
    private val schedule: Scheduler,
    private val notice: (String) -> Unit = {},
    /**
     * Le robot a enregistré la recette [id] (créée, copiée ou modifiée) : catalogue et fiche à relire ;
     * [leaving] : l'éditeur vient de se fermer, la fiche de cette recette est à montrer.
     */
    private val onSaved: (id: String, leaving: Boolean) -> Unit = { _, _ -> },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newDraftId: () -> String = { randomId() },
    /** Brouillon écrit ce délai après le dernier changement (et aussitôt en quittant). */
    private val persistDelayMs: Long = 400,
) : EditActions, RobotLink.Listener {
    var state = EditeurState()
        private set
    var stepState: EtapeState? = null
        private set

    /** Brouillon affiché ; null tant que rien n'est chargé. */
    var draft: Draft? = null
        private set
    /** Brouillon trouvé à l'ouverture, proposé (« Reprendre le brouillon »). */
    private var offered: Draft? = null
    /** Recette du robot à ouvrir si le brouillon proposé est écarté (null : une recette nouvelle). */
    private var openingId: String? = null
    /** Une copie « Ma version » à demander de nouveau après un échec ([retryLoad]). */
    private var copyOf: String? = null
    private var limits = EditLimitsWire()
    /** Ce que l'éditeur d'étape du robot propose ; null : le robot ne prend pas les réglages (ou pas encore lu). */
    private var settings: StepSettingsWire? = null
    /** GET /api/editor déjà demandé pendant cette ouverture (une erreur réseau le permet de nouveau). */
    private var editorAsked = false
    /** Feuille « Réglage du robot » ouverte sur l'étape [step], ou null. */
    private var choice: SettingChoice? = null
    /** Le refus du robot à l'étape [refusedStep] porte sur son réglage. */
    private var refusedSetting = false
    /** Des changements que le robot n'a pas encore enregistrés. */
    private var dirty = false
    private var saving = false
    private var banner: EditBanner? = null
    private var problemField: String? = null
    private var problemText: String? = null
    private var refusedStep = -1
    private var refusedText: String? = null
    /** Étape ouverte dans l'écran Étape, -1 sinon ; [stepIsNew] : elle vient d'être ajoutée. */
    private var step = -1
    private var stepIsNew = false
    private var focusLine = -1
    private var focusSeq = 0
    private var gen = 0
    private var cancelPersist: (() -> Unit)? = null
    private val unsubscribe = link.addListener(this)

    private val online get() = link.status.phase == LinkPhase.CONNECTED

    // ------------------------------------------------------------------ ouverture

    /** « Écrire une recette » : un brouillon de recette nouvelle déjà là est proposé d'abord. */
    fun startNew() {
        reset(tr("Nouvelle recette"))
        openingId = null
        val old = drafts.latestNew()
        if (old != null) offer(old) else {
            draft = Draft(newDraftId())
            show(EditPhase.EDITING)
            ensureSettings()
        }
        nav.open(Route.EDITEUR)
    }

    /** « Modifier » une recette perso : son brouillon s'il y en a un, sinon la recette telle que le robot l'a enregistrée. */
    fun startEdit(id: String) {
        reset(tr("Modifier la recette"))
        openingId = id
        val old = drafts.load(id)
        if (old != null) offer(old) else load(id)
        nav.open(Route.EDITEUR)
    }

    /** « Ma version » confirmé : le robot crée la copie (elle est alors dans « Mes recettes »), l'éditeur l'ouvre. */
    fun startCopy(id: String) {
        reset(tr("Ma version"))
        copyOf = id
        copy(id)
        nav.open(Route.EDITEUR)
    }

    private fun reset(heading: String) {
        flush()
        gen++
        state = EditeurState(heading = heading)
        draft = null
        offered = null
        copyOf = null
        dirty = false
        saving = false
        banner = null
        clearProblem()
        step = -1
        stepIsNew = false
        settings = null
        editorAsked = false
        choice = null
    }

    private fun offer(d: Draft) {
        offered = d
        show(EditPhase.OFFER, tr("Brouillon du %s, gardé sur le téléphone.", stamp(d.changedAt)))
    }

    private fun stamp(ms: Long): String {
        val f = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ROOT)
        return f.format(java.util.Date(ms))
    }

    private fun load(id: String) {
        val g = gen
        show(EditPhase.LOADING, tr("Lecture de la recette sur le robot…"))
        link.editable(id) { r ->
            if (g != gen) return@editable
            r.onSuccess { e -> adopt(e) }.onFailure { show(EditPhase.ERROR, message(it)) }
        }
    }

    private fun copy(id: String) {
        val g = gen
        show(EditPhase.LOADING, tr("Création de votre version sur le robot…"))
        link.copyRecipe(id) { r ->
            if (g != gen) return@copyRecipe
            r.onSuccess { e ->
                copyOf = null
                onSaved(e.id, false)
                adopt(e)
            }.onFailure { show(EditPhase.ERROR, message(it)) }
        }
    }

    private fun adopt(e: EditableRecipe) {
        limits = e.limits
        settings = e.settings
        editorAsked = true
        draft = Draft.of(e, newDraftId())
        dirty = false
        show(EditPhase.EDITING)
    }

    override fun resumeDraft() {
        val d = offered ?: return
        offered = null
        draft = d
        dirty = true
        show(EditPhase.EDITING)
        ensureSettings()
    }

    override fun discardDraft() {
        val d = offered ?: return
        offered = null
        drafts.delete(d.key)
        val id = openingId
        if (id == null) {
            draft = Draft(newDraftId())
            dirty = false
            show(EditPhase.EDITING)
            ensureSettings()
        } else load(id)
    }

    /**
     * Brouillon sans recette lue sur le robot (recette nouvelle, brouillon repris) : ce que l'éditeur d'étape
     * du robot propose, par GET /api/editor. Un robot qui ne prend pas les réglages ne l'annonce pas (ou ne
     * connaît pas la route) : rien n'est proposé ; une erreur réseau laisse réessayer au retour du robot.
     */
    private fun ensureSettings() {
        if (settings != null || editorAsked || draft == null || !online) return
        editorAsked = true
        val g = gen
        link.editor { r ->
            if (g != gen) return@editor
            r.onSuccess { e ->
                settings = e.settings
                limits = e.limits
            }.onFailure { t -> if (t !is CompanionException || t.status == 504) editorAsked = false }
            if (state.phase == EditPhase.EDITING) show()
            if (step >= 0) showStep()
        }
    }

    override fun retryLoad() {
        val c = copyOf
        val id = openingId
        when {
            c != null -> copy(c)
            id != null -> load(id)
        }
    }

    // ------------------------------------------------------------------ changements (brouillon aussitôt)

    private fun change(f: (Draft) -> Draft) {
        val d = draft ?: return
        val next = f(d)
        if (next == d) return
        draft = next.copy(changedAt = now())
        dirty = true
        cancelPersist?.invoke()
        cancelPersist = schedule(persistDelayMs, Runnable {
            cancelPersist = null
            persist()
        })
    }

    /** Écrit le brouillon sur le téléphone ; une recette nouvelle encore vide n'en laisse pas. */
    private fun persist() {
        val d = draft ?: return
        if (!dirty) return
        if (d.recipeId == null && d.title.isBlank() && d.groups.all { g -> g.items.all { it.isBlank() } } && d.steps.isEmpty()) drafts.delete(d.key)
        else drafts.save(d)
    }

    /** Écrit le brouillon en attente tout de suite (quitter l'éditeur, fin de l'activité). */
    fun flush() {
        cancelPersist?.invoke()
        cancelPersist = null
        persist()
    }

    override fun setTitle(title: String) {
        change { it.copy(title = title) }
        if (problemField == "title") clearProblem()
        show()
    }

    override fun setServings(n: Int) {
        change { it.copy(servings = n) }
        if (problemField == "servings") clearProblem()
        show()
    }

    override fun setMinutes(m: Int) {
        change { it.copy(minutes = m) }
        if (problemField == "totalMinutes") clearProblem()
        show()
    }

    override fun setDifficulty(d: Int) {
        change { it.copy(difficulty = d) }
        show()
    }

    override fun addIngredient(after: Int) {
        val d = draft ?: return
        val lines = d.lines
        val groups = d.groups.map { it.items.toMutableList() }.toMutableList()
        val titles = d.groups.map { it.title }.toMutableList()
        val at: Int
        if (groups.isEmpty()) {
            groups += mutableListOf("")
            titles += ""
            at = 0
        } else if (after in lines.indices) {
            val (g, k) = lines[after]
            groups[g].add(k + 1, "")
            at = after + 1
        } else {
            groups.last().add("")
            at = lines.size
        }
        change { it.copy(groups = groups.indices.map { i -> DraftGroup(titles[i], groups[i]) }) }
        if (problemField == "content") clearProblem()
        focusLine = at
        focusSeq++
        show()
    }

    override fun setIngredient(index: Int, text: String) {
        val d = draft ?: return
        val (g, k) = d.lines.getOrNull(index) ?: return
        change { it.copy(groups = it.groups.mapIndexed { i, gr -> if (i != g) gr else gr.copy(items = gr.items.mapIndexed { j, t -> if (j == k) text else t }) }) }
        if (problemField == "ingredients" || problemField == "content") clearProblem()
        show()
    }

    override fun removeIngredient(index: Int) {
        val d = draft ?: return
        val (g, k) = d.lines.getOrNull(index) ?: return
        change { it.copy(groups = it.groups.mapIndexed { i, gr -> if (i != g) gr else gr.copy(items = gr.items.filterIndexed { j, _ -> j != k }) }
            .filter { gr -> gr.items.isNotEmpty() }) }
        show()
    }

    override fun addStep() {
        val d = draft ?: return
        change { it.copy(steps = it.steps + DraftStep()) }
        if (problemField == "content") clearProblem()
        step = d.steps.size
        stepIsNew = true
        showStep()
        show()
        nav.open(Route.ETAPE)
    }

    override fun openStep(index: Int) {
        val d = draft ?: return
        if (index !in d.steps.indices) return
        step = index
        stepIsNew = false
        choice = null
        showStep()
        nav.open(Route.ETAPE)
    }

    override fun moveStep(index: Int, delta: Int) {
        val d = draft ?: return
        val j = index + delta
        if (index !in d.steps.indices || j !in d.steps.indices) return
        change {
            val l = it.steps.toMutableList()
            l.add(j, l.removeAt(index))
            it.copy(steps = l)
        }
        if (refusedStep == index) refusedStep = j else if (refusedStep == j) refusedStep = index
        show()
    }

    override fun setStepText(text: String) {
        val i = step
        change { it.copy(steps = it.steps.mapIndexed { k, s -> if (k == i) s.copy(text = text) else s }) }
        if (refusedStep == i) clearProblem()
        showStep()
        show()
    }

    override fun setStepGrams(grams: Int) {
        val i = step
        change { it.copy(steps = it.steps.mapIndexed { k, s -> if (k == i) s.copy(grams = grams.coerceAtLeast(0)) else s }) }
        if (refusedStep == i) clearProblem()
        showStep()
        show()
    }

    override fun removeStep() {
        val i = step
        if (i < 0) return
        choice = null
        change { it.copy(steps = it.steps.filterIndexed { k, _ -> k != i }) }
        if (refusedStep == i) clearProblem() else if (refusedStep > i) refusedStep--
        step = -1
        show()
        nav.close(Route.ETAPE)
    }

    /** Retour de l'écran Étape : une étape ajoutée et laissée vide s'en va. */
    override fun closeStep() {
        val i = step
        val s = draft?.steps?.getOrNull(i)
        step = -1
        choice = null
        if (s != null && s.from == null && s.text.isBlank() && s.grams == 0 && s.edit?.machine == null) {
            change { it.copy(steps = it.steps.filterIndexed { k, _ -> k != i }) }
            show()
        }
        nav.close(Route.ETAPE)
    }

    // ------------------------------------------------------------------ réglage du robot (feuille)

    private fun stepSettings(): Pair<DraftStep, StepSettingsWire>? {
        val s = draft?.steps?.getOrNull(step) ?: return null
        val st = settings ?: return null
        return s to st
    }

    override fun openSetting() {
        val (s, st) = stepSettings() ?: return
        // Brouillon de 0.3.9 : le réglage enregistré n'est connu que par son résumé ; rien n'est choisi tant qu'on n'y touche pas.
        choice = if (s.edit == null && s.stored == null && s.machine != null) SettingChoice(keep = true) else SettingChoice.start(st, s.setting)
        showStep()
    }

    override fun pickSetting(program: String) {
        val c = choice ?: return
        val (_, st) = stepSettings() ?: return
        choice = c.pick(st, if (program.isEmpty()) null else StepProgram.values().firstOrNull { it.key == program } ?: return)
        showStep()
    }

    override fun stepSetting(field: String, dir: Int) {
        val c = choice ?: return
        val (_, st) = stepSettings() ?: return
        choice = c.step(st, field, dir)
        showStep()
    }

    override fun setSettingReverse(reverse: Boolean) {
        choice = (choice ?: return).reverse(reverse)
        showStep()
    }

    override fun setSettingWhisk(whisk: Boolean) {
        choice = (choice ?: return).whisk(whisk)
        showStep()
    }

    /** « Utiliser ce réglage » : gardé dans le brouillon ; égal au réglage enregistré, il redevient « pas touché ». */
    override fun applySetting() {
        val c = choice ?: return
        choice = null
        val i = step
        val st = settings
        if (!c.keep && st != null) {
            val m = c.machine
            change { d ->
                d.copy(steps = d.steps.mapIndexed { k, s ->
                    if (k != i) s
                    else s.copy(edit = if (c.unchanged(st, s.stored) && (s.stored != null || s.machine == null)) null else SettingEdit(m))
                })
            }
            if (refusedStep == i) clearProblem()
        }
        showStep()
        show()
    }

    /** « Reprendre le réglage enregistré » : le réglage tapé ici s'en va, l'étape garde celui du robot. */
    override fun restoreSetting() {
        val i = step
        choice = null
        change { d -> d.copy(steps = d.steps.mapIndexed { k, s -> if (k == i) s.copy(edit = null) else s }) }
        if (refusedStep == i) clearProblem()
        showStep()
        show()
    }

    override fun closeSetting() {
        if (choice == null) return
        choice = null
        showStep()
    }

    override fun leave() {
        choice = null
        flush()
        nav.close(Route.ETAPE)
        nav.close(Route.EDITEUR)
    }

    // ------------------------------------------------------------------ enregistrer sur le robot

    override fun save() {
        val d = draft ?: return
        if (saving || state.phase != EditPhase.EDITING) return
        flush()
        clearProblem()
        if (!online) {
            banner = EditBanner(EditBanner.Kind.OFFLINE, tr("Le robot ne répond pas"), tr("Le brouillon reste sur le téléphone : enregistrez quand le robot sera joignable."))
            return show()
        }
        val req = d.request()
        if (Json.write(req.toMap()).toByteArray(Charsets.UTF_8).size > limits.maxBodyBytes) {
            banner = EditBanner(EditBanner.Kind.REFUSED, tr("Recette trop longue pour le robot."), tr("Raccourcissez les étapes les plus longues, puis enregistrez de nouveau."))
            return show()
        }
        saving = true
        banner = null
        show()
        val g = gen
        link.writeRecipe(d.recipeId, req) { r ->
            if (g != gen) return@writeRecipe
            saving = false
            r.onSuccess { e -> saved(d, e) }.onFailure { refused(it) }
        }
    }

    private fun saved(sent: Draft, e: EditableRecipe) {
        drafts.delete(sent.key)
        limits = e.limits
        settings = e.settings
        val cur = draft
        val edited = cur != null && cur.copy(changedAt = 0) != sent.copy(changedAt = 0)
        if (edited && cur != null) {
            onSaved(e.id, false)
            // Changé pendant l'envoi : le brouillon continue, sur la recette et la version que le robot vient de donner.
            draft = Draft.rebase(cur.copy(recipeId = e.id), e)
            persist()
            notice(tr("Recette enregistrée sur le robot"))
            return show()
        }
        draft = Draft.of(e, sent.draftId)
        dirty = false
        notice(tr("Recette enregistrée sur le robot"))
        show()
        nav.close(Route.ETAPE)
        nav.close(Route.EDITEUR)
        onSaved(e.id, true)
    }

    private fun refused(t: Throwable) {
        val e = t as? CompanionException
        val err = e?.error
        when {
            e == null || e.status == 504 -> banner = EditBanner(EditBanner.Kind.OFFLINE, tr("Le robot ne répond pas"),
                tr("Le brouillon reste sur le téléphone : enregistrez quand le robot sera joignable."))
            e.status == 413 -> banner = EditBanner(EditBanner.Kind.REFUSED, tr("Recette trop longue pour le robot."),
                tr("Raccourcissez les étapes les plus longues, puis enregistrez de nouveau."))
            e.status == 409 && err!!.code == "stale" -> banner = EditBanner(EditBanner.Kind.STALE, tr("La recette a changé sur le robot"),
                tr("Reprenez la version du robot, ou gardez votre brouillon et enregistrez-le par-dessus."))
            e.status == 422 -> {
                val where = err!!.step
                banner = EditBanner(EditBanner.Kind.REFUSED, tr("Le robot n'a pas enregistré la recette."),
                    if (where >= 0) tr("Corrigez l'étape %d, puis enregistrez de nouveau.", where + 1) else err.message)
                if (where >= 0 && where < (draft?.steps?.size ?: 0)) {
                    refusedStep = where
                    refusedText = err.message
                    refusedSetting = err.code == "step_machine"
                    choice = null
                    show()
                    // L'étape refusée s'ouvre, avec le message du robot.
                    step = where
                    stepIsNew = false
                    showStep()
                    if (nav.current != Route.ETAPE) nav.open(Route.ETAPE)
                    return
                }
                problemField = err.field.ifBlank { null }
                problemText = err.message
            }
            else -> banner = EditBanner(EditBanner.Kind.REFUSED, tr("Le robot n'a pas enregistré la recette."), message(t))
        }
        show()
    }

    override fun reloadFromRobot() {
        val d = draft ?: return
        val id = d.recipeId ?: return
        drafts.delete(d.key)
        banner = null
        clearProblem()
        load(id)
    }

    /** Garder le brouillon : posé sur la version que le robot a maintenant, puis enregistré par-dessus à la demande. */
    override fun keepDraft() {
        val d = draft ?: return
        val id = d.recipeId ?: return
        val g = gen
        banner = null
        // Pas d'enregistrement tant que la version du robot n'est pas arrivée.
        saving = true
        show()
        link.editable(id) { r ->
            if (g != gen) return@editable
            saving = false
            r.onSuccess { e ->
                limits = e.limits
                settings = e.settings
                editorAsked = true
                draft = Draft.rebase(draft ?: d, e)
                dirty = true
                persist()
                show()
            }.onFailure {
                banner = EditBanner(EditBanner.Kind.OFFLINE, tr("Le robot ne répond pas"), message(it))
                show()
            }
        }
    }

    // ------------------------------------------------------------------ rendu

    private fun clearProblem() {
        problemField = null
        problemText = null
        refusedStep = -1
        refusedText = null
        refusedSetting = false
    }

    /** Note sous le réglage d'une étape chez un robot qui prend les réglages ; [short] : la ligne de la liste des étapes. */
    private fun settingNote(s: DraftStep, short: Boolean): String = when {
        s.typed -> tr("Nouveau réglage : à confirmer une fois sur le robot avant le premier lancement")
        s.removed -> tr("Le réglage enregistré sera retiré à l'enregistrement")
        s.summary == null -> if (short) "" else tr("Étape sans le robot : couper, verser, réserver…")
        s.review -> tr("À vérifier sur le robot avant le premier lancement")
        else -> if (short) "" else tr("Enregistré sur le robot")
    }

    private fun message(t: Throwable): String {
        val e = t as? CompanionException ?: return tr("Le robot ne répond pas pour l'instant.")
        return when (e.status) {
            404 -> tr("Recette introuvable sur le robot")
            409, 422 -> e.error.message.ifBlank { tr("Le robot a refusé la demande") }
            else -> tr("Le robot a refusé la demande")
        }
    }

    private fun show(phase: EditPhase = state.phase, message: String? = if (phase == state.phase) state.message else null) {
        val d = draft
        val lines = ArrayList<EditLine>()
        if (d != null) for (group in d.groups) for ((k, t) in group.items.withIndex())
            lines += EditLine(group.title, t, k == 0 && group.title.isNotEmpty())
        val note = when {
            !online -> tr("Robot injoignable : le brouillon reste sur le téléphone")
            dirty -> tr("Brouillon gardé sur le téléphone")
            d?.recipeId != null -> tr("Enregistrée sur le robot")
            else -> ""
        }
        val s = EditeurState(
            heading = state.heading, phase = phase, message = message,
            title = d?.title.orEmpty(), servings = d?.servings ?: 4, minutes = d?.minutes ?: 0, difficulty = d?.difficulty ?: 1,
            lines = lines,
            steps = d?.steps.orEmpty().mapIndexed { i, st ->
                EditStepRow(st.text, st.grams, st.summary, st.review, if (i == refusedStep) refusedText else null,
                    settingNote = if (settings != null) settingNote(st, short = true) else null, typed = st.typed)
            },
            saving = saving, banner = banner, problemField = problemField, problemText = problemText, note = note,
            focusLine = focusLine, focusSeq = focusSeq, maxServings = limits.maxServings, maxMinutes = limits.maxTotalMinutes,
            dirty = dirty,
        )
        state = s
        render(s)
    }

    private fun showStep() {
        val d = draft ?: return
        val s = d.steps.getOrNull(step) ?: return
        val set = settings
        val c = choice
        val st = EtapeState(step + 1, s.text, s.grams, s.summary, s.review, if (step == refusedStep) refusedText else null,
            isNew = stepIsNew, maxGrams = limits.maxGrams, editable = set != null,
            settingNote = if (set != null) settingNote(s, short = false) else null, typed = s.typed,
            settingRefused = step == refusedStep && refusedSetting,
            sheet = if (set != null && c != null) SettingChoice.state(step + 1, set, c, s.stored, s.summary, canRestore = s.edit != null && s.from != null)
                else null)
        stepState = st
        renderStep(st)
    }

    override fun onStatus(status: LinkStatus) {
        if (draft != null) show()
        if (state.phase == EditPhase.EDITING) ensureSettings()
    }

    fun close() {
        flush()
        unsubscribe()
    }

    companion object {
        private val random = SecureRandom()

        fun randomId(): String {
            val b = ByteArray(16)
            random.nextBytes(b)
            val sb = StringBuilder()
            for (x in b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x.toInt() and 0xFF))
            return sb.toString()
        }
    }
}
