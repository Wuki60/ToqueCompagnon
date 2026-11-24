package app.toque.companion.flows

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.JournalActions
import app.toque.companion.ui.Link
import app.toque.companion.ui.screens.JournalDay
import app.toque.companion.ui.screens.JournalEntry
import app.toque.companion.ui.screens.JournalFilter
import app.toque.companion.ui.screens.JournalKind
import app.toque.companion.ui.screens.JournalOutcome
import app.toque.companion.ui.screens.JournalState
import app.toque.core.companion.ActivityWire
import app.toque.core.companion.JournalPage
import app.toque.core.companion.StateWire
import app.toque.core.companion.client.CompanionException
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import java.util.Calendar

/**
 * Onglet Journal branché sur la liaison : les activités du robot (recettes, cuissons, programmes),
 * groupées par jour dans la langue du téléphone, avec ce que le robot en dit (heures, réglages,
 * étapes lancées, la ligne qui raconte un arrêt). Rien n'est inventé : les étapes d'une recette
 * sont celles que le journal a vu partir, avec leurs réglages. Rechargé quand l'écran paraît et
 * quand le journal du robot bouge pendant qu'il est affiché. Kotlin pur, fil principal.
 */
class JournalFlow(
    private val link: RobotLink,
    private val render: (JournalState) -> Unit,
    private val clock: PhoneClock = PhoneClock(),
    private val limit: Int = 300,
    /** Ouvre la fiche d'une recette du catalogue (onglet Recettes). */
    private val open: (recipeId: String) -> Unit = {},
) : JournalActions, RobotLink.Listener {
    var state = JournalState(loading = true)
        private set
    private var page: JournalPage? = null
    private var visible = false
    private var loading = false
    private var loadedSeq = -1L
    private var latestSeq = 0L
    /**
     * Recettes du journal encore au catalogue du robot (true) ou supprimées (false) ; absente : pas
     * encore vérifiée. Seules les premières ouvrent leur fiche : pas de carte qui mène à une erreur.
     */
    private val exists = HashMap<String, Boolean>()
    /** Recettes à vérifier, une demande à la fois (les photos et le catalogue gardent leurs places). */
    private val toCheck = ArrayDeque<String>()
    private var checking: String? = null
    // Après les champs ci-dessus : la liaison peut rappeler pendant l'inscription.
    private val unsubscribe = link.addListener(this)

    fun close() = unsubscribe()

    /** L'écran Journal paraît ([shown] vrai) ou disparaît. */
    fun setVisible(shown: Boolean) {
        visible = shown
        if (shown) {
            // Une recette supprimée a pu revenir (synchronisation) : vérifiée de nouveau.
            exists.entries.removeAll { !it.value }
            load()
        }
    }

    override fun openRecipe(recipeId: String) {
        if (exists[recipeId] == true) open(recipeId)
    }

    /** Vérifie, une à une, les recettes du journal dont on ne sait pas encore si elles existent. */
    private fun check(ids: Collection<String>) {
        for (id in ids) if (id !in exists && id != checking && id !in toCheck) toCheck.addLast(id)
        next()
    }

    private fun next() {
        if (checking != null) return
        val id = toCheck.removeFirstOrNull() ?: return
        checking = id
        link.recipe(id) { r ->
            checking = null
            r.onSuccess { exists[id] = true }
            r.onFailure { e -> if (e is CompanionException && e.status == 404) exists[id] = false }
            // Réseau en panne : on réessaiera au prochain chargement ; sinon la carte change.
            if (id in exists && page != null) publish()
            if (r.isSuccess || exists[id] == false) next() else toCheck.clear()
        }
    }

    override fun filter(filter: JournalFilter) {
        state = state.copy(filter = filter)
        publish()
    }

    override fun onState(state: StateWire, receivedMono: Long) {
        clock.observe(state)
        latestSeq = state.journalSeq
        if (visible && latestSeq != loadedSeq) load()
    }

    override fun onStatus(status: LinkStatus) {
        val l = when (status.phase) {
            LinkPhase.CONNECTED -> Link.CONNECTED
            LinkPhase.UNREACHABLE -> Link.OFFLINE
            else -> Link.CONNECTING
        }
        if (status.phase == LinkPhase.NOT_PAIRED || status.phase == LinkPhase.REMOVED) {
            page = null
            loadedSeq = -1
            exists.clear()
            toCheck.clear()
        }
        if (l != state.link) {
            state = state.copy(link = l)
            render(state)
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        val seq = latestSeq
        link.journal(0, limit) { r ->
            loading = false
            r.onSuccess {
                page = it
                loadedSeq = maxOf(seq, it.lastSeq)
                publish()
            }.onFailure {
                if (page == null) {
                    state = state.copy(loading = false)
                    render(state)
                }
            }
            if (visible && latestSeq > loadedSeq && r.isSuccess) load()
        }
    }

    private fun publish() {
        val p = page
        state = if (p == null) state.copy(days = emptyList()) else state.copy(days = present(p, state.filter), loading = false)
        render(state)
        if (p != null) check(p.activities.filter { it.kind == "recipe" && it.recipeId.isNotEmpty() }.map { it.recipeId }.distinct())
    }

    /** Les jours de l'écran pour la page [p] du journal, filtrés par [filter] (du plus récent au plus ancien). */
    fun present(p: JournalPage, filter: JournalFilter): List<JournalDay> {
        val now = clock.now()
        val groups = LinkedHashMap<String, MutableList<JournalEntry>>()
        for (a in p.activities.sortedByDescending { it.startedAt }) {
            val kind = kindOf(a)
            val keep = when (filter) {
                JournalFilter.ALL -> true
                JournalFilter.RECIPES -> kind == JournalKind.RECIPE
                JournalFilter.MANUAL -> kind == JournalKind.MANUAL
                JournalFilter.PROGRAMS -> kind != JournalKind.RECIPE && kind != JournalKind.MANUAL
            }
            if (!keep) continue
            groups.getOrPut(dayTitle(clock.phone(a.startedAt), now)) { ArrayList() } += entry(a, kind, p)
        }
        return groups.map { (title, list) -> JournalDay(title, list) }
    }

    private fun entry(a: ActivityWire, kind: JournalKind, p: JournalPage): JournalEntry {
        val lines = p.entries.filter { it.activityId == a.id }.sortedBy { it.seq }
        val start = clock.hhmm(a.startedAt)
        val end = if (a.endedAt > 0) clock.hhmm(a.endedAt) else ""
        val detail = when (kind) {
            JournalKind.RECIPE ->
                if (end.isNotEmpty()) tr("%s – %s · recette, %d étapes", start, end, a.stepCount) else tr("%s · recette, %d étapes", start, a.stepCount)
            JournalKind.MANUAL -> listOf(if (end.isNotEmpty()) "$start – $end" else start, a.summary).filter { it.isNotBlank() }.joinToString(" · ")
            else -> tr("%s · programme, %s", start, a.summary.ifBlank { Fmt.duration((a.totalMs / 1000).toInt()) })
        }
        val outcome = when (a.outcome) {
            "running" -> JournalOutcome.RUNNING
            "finished" -> JournalOutcome.FINISHED
            else -> JournalOutcome.STOPPED
        }
        // Étapes de cuisson vues par le journal : « Étape 3 » et les réglages que la ligne donne.
        val steps = if (kind != JournalKind.RECIPE) emptyList() else lines.filter { it.kind == "step_started" && it.stepIndex >= 0 }
            .distinctBy { it.stepIndex }.map { tr("Étape %d", it.stepIndex + 1) to settingsOf(it.text) }
        val notes = ArrayList<String>()
        if (kind == JournalKind.RECIPE && a.handSteps > 0) notes += tr("Et %d étapes à la main", a.handSteps)
        // Cuisson manuelle : quand la température demandée a été atteinte (« Température atteinte : 100 °C à 19:04 »).
        if (kind == JournalKind.MANUAL) lines.lastOrNull { it.kind == "temp_reached" }?.let { notes += tr("%s à %s", it.text, clock.hhmm(it.at)) }
        // Une fin qui n'est pas « terminée » : la phrase du robot (qui, quand, pourquoi).
        if (outcome == JournalOutcome.STOPPED)
            lines.lastOrNull { it.kind in END_KINDS }?.text?.takeIf { it.isNotBlank() }?.let { notes += it }
        return JournalEntry("a${a.id}", kind, a.title, detail, outcome, steps, notes.joinToString(" · ").ifEmpty { null },
            recipeId = a.recipeId.takeIf { kind == JournalKind.RECIPE && it.isNotEmpty() && exists[it] == true })
    }

    private fun dayTitle(phoneWall: Long, now: Long): String {
        val zone = clock.zone()
        fun day(t: Long) = Calendar.getInstance(zone).apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
        val yesterday = Calendar.getInstance(zone).apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        return when (day(phoneWall)) {
            day(now) -> tr("Aujourd'hui")
            day(yesterday) -> tr("Hier")
            else -> clock.format("EEEE d MMMM", I18n.locale, phoneWall).replaceFirstChar { it.titlecase(I18n.locale) }
        }
    }

    companion object {
        private val END_KINDS = setOf("job_stopped", "job_cancelled", "job_fault", "recipe_abandoned")
        private val PROGRAMS = listOf("Pétrir" to JournalKind.KNEAD, "Vapeur" to JournalKind.STEAM,
            "Rissoler" to JournalKind.TOAST, "Nettoyage" to JournalKind.CLEAN)

        /**
         * Sorte d'activité. Un programme se reconnaît à son titre, écrit dans la langue du robot :
         * comparé au nom du programme dans chaque langue connue.
         */
        fun kindOf(a: ActivityWire): JournalKind = when (a.kind) {
            "recipe" -> JournalKind.RECIPE
            "manual" -> PROGRAMS.firstOrNull { (fr, _) -> named(a.title, fr) }?.second ?: JournalKind.MANUAL
            else -> PROGRAMS.firstOrNull { (fr, _) -> named(a.title, fr) }?.second ?: JournalKind.KNEAD
        }

        private fun named(title: String, fr: String): Boolean =
            title.equals(fr, ignoreCase = true) || I18n.languages.any { it.texts[fr]?.equals(title, ignoreCase = true) == true }

        /** « Étape 3 lancée : 3 min · 100 °C · vitesse 1 » → « 3 min · 100 °C · vitesse 1 ». */
        fun settingsOf(text: String): String {
            val i = text.indexOf(':')
            return if (i >= 0) text.substring(i + 1).trim() else text
        }
    }
}
