package app.toque.companion.ui.screens

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.ui.EmptyState
import app.toque.companion.ui.ChipRow
import app.toque.companion.ui.Icon
import app.toque.companion.ui.IconBadge
import app.toque.companion.ui.JournalActions
import app.toque.companion.ui.Link
import app.toque.companion.ui.LiveHeader
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.core.i18n.tr

/** Filtres du journal. */
enum class JournalFilter { ALL, RECIPES, MANUAL, PROGRAMS }

/** Ce qu'une ligne du journal raconte ; l'icône est celle du robot (programmes : celle de l'écran Programme). */
enum class JournalKind(val icon: Icon.Kind, internal val feminine: Boolean) {
    RECIPE(Icon.Kind.BOOK, true),
    MANUAL(Icon.Kind.POT, true),
    KNEAD(Icon.Kind.KNEAD, false),
    STEAM(Icon.Kind.STEAM, false),
    TOAST(Icon.Kind.FLAME, false),
    CLEAN(Icon.Kind.DROP, false),
}

/** Fin d'une activité : texte accordé par l'écran (« Terminée » pour une recette, « Terminé » pour un programme). */
enum class JournalOutcome { FINISHED, STOPPED, RUNNING }

/** Journal d'activité du robot (racine de l'onglet Journal, maquette « Journal »). */
data class JournalState(
    val link: Link = Link.CONNECTED,
    val filter: JournalFilter = JournalFilter.ALL,
    /** Jours, du plus récent au plus ancien ; vide : écran « rien pour l'instant ». */
    val days: List<JournalDay> = emptyList(),
    /** Journal en cours de chargement (premier affichage). */
    val loading: Boolean = false,
)

/** Un jour : « Aujourd'hui », « Hier », « Lundi 17 novembre » (déjà formaté). */
data class JournalDay(val title: String, val entries: List<JournalEntry>)

data class JournalEntry(
    val id: String,
    val kind: JournalKind,
    val title: String,
    /** « 12:05 – 12:41 · recette, 6 étapes », « 09:15 · programme, 45 s ». */
    val detail: String,
    val outcome: JournalOutcome,
    /** Étapes de cuisson d'une recette : nom et réglages (« Cuire », « 21 min · 100 °C · vitesse 1 »). */
    val steps: List<Pair<String, String>> = emptyList(),
    /** Ligne grise du bas (« Arrêtée depuis ce téléphone après 2 min 10 »), ou null. */
    val note: String? = null,
    /**
     * Recette du catalogue à ouvrir quand on touche la carte (elle existe encore sur le robot) ; null :
     * la carte ne se touche pas (cuisson, programme, recette supprimée ou robot plus ancien).
     */
    val recipeId: String? = null,
)

class JournalScreen(c: Context, private val actions: JournalActions, private val nav: Navigator) : Screen<JournalState>(c) {
    override val tab = Tab.LOG
    private val header = LiveHeader(c, tr("Journal"))
    /** Pastilles sur une ligne qui défile (comme Recettes) : fixées en haut, elles ne prennent qu'une ligne. */
    private lateinit var chips: ChipRow<JournalFilter>
    private val filters by lazy { listOf(
        JournalFilter.ALL to tr("Tout"),
        JournalFilter.RECIPES to tr("Recettes"),
        JournalFilter.MANUAL to tr("Manuel"),
        JournalFilter.PROGRAMS to tr("Programmes"),
    ) }
    private val list = Ui.vertical(c)
    private val empty = EmptyState(c, Icon.Kind.LIST)
    /** Vues de la liste, dans l'ordre : titres de jour ([TextView]) et cartes ([EntryCard]). */
    private val rows = ArrayList<View>()
    private val cards = HashMap<View, EntryCard>()

    override fun build(): View {
        // En-tête et pastilles fixés en haut (comme Recettes) ; seule la liste défile.
        val root = Ui.vertical(c)
        root.addView(header, Ui.lp())
        val column = Ui.vertical(c)
        chips = ChipRow(c) { if (it != state?.filter) actions.filter(it) }
        chips.setPadding(Ui.dp(20), 0, Ui.dp(20), 0)
        chips.set(filters, JournalFilter.ALL)
        root.addView(chips, Ui.lp(t = 10, b = 4))
        column.addView(list, Ui.lp(l = 20, r = 20))
        column.addView(empty, Ui.lp(t = 24))
        column.addView(Ui.spacer(c, h = 12))
        root.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))
        return root
    }

    override fun bind(s: JournalState, old: JournalState?) {
        header.set(s.link)
        chips.set(filters, s.filter)
        // Lignes à plat (titre de jour, cartes…) ; une vue de même nature est réutilisée sur place.
        var i = 0
        for (day in s.days) {
            val t = row(i++, isTitle = true) as TextView
            Ui.set(t, day.title)
            (t.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(18)
            for (e in day.entries) {
                val v = row(i++, isTitle = false)
                cards.getValue(v).bind(e)
                (v.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(10)
            }
        }
        while (rows.size > i) {
            val v = rows.removeAt(rows.size - 1)
            cards.remove(v)
            list.removeView(v)
        }
        val none = s.days.all { it.entries.isEmpty() }
        Ui.show(empty, none)
        if (none) when {
            s.loading -> empty.set(tr("Chargement du journal…"), "", loading = true)
            s.filter != JournalFilter.ALL -> empty.set(tr("Rien dans cette catégorie"), tr("Choisissez « Tout » pour voir toute l'activité du robot."))
            else -> empty.set(tr("Rien pour l'instant"), tr("Les recettes, cuissons et programmes lancés sur le robot s'afficheront ici, avec ce que ce téléphone a fait."))
        }
    }

    /** La vue n° [index] de la liste, de la bonne nature (titre ou carte), créée ou remplacée au besoin. */
    private fun row(index: Int, isTitle: Boolean): View {
        val existing = rows.getOrNull(index)
        if (existing != null && (existing !in cards) == isTitle) return existing
        val v: View = if (isTitle) Ui.overline(c) else EntryCard(c) { actions.openRecipe(it) }.also { cards[it.root] = it }.root
        v.layoutParams = Ui.lp()
        if (existing != null) {
            list.removeViewAt(index)
            cards.remove(existing)
            rows[index] = v
        } else rows += v
        list.addView(v, index)
        return v
    }

    /** Une carte : pastille d'icône, titre, détail, issue ; étapes et note en dessous si présentes. */
    private class EntryCard(val c: Context, private val open: (String) -> Unit) {
        val root: LinearLayout = Ui.card(c)
        /** Chevron des cartes qui ouvrent leur recette. */
        private val chevron = Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private var recipeId: String? = null
        private val badge = IconBadge(c, Icon.Kind.BOOK)
        private val title = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT, Ui.MEDIUM))
        private val detail = Ui.ellipsize(Ui.tabular(Ui.text(c, "", 13f, Ui.TEXT2)), 2)
        private val outcome = Ui.text(c, "", 13f, Ui.OK)
        private val steps = Ui.vertical(c)
        private val stepRows = ArrayList<Pair<TextView, TextView>>()
        private val stepsNote = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)
        private val note = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)

        init {
            Ui.pad(root, 14, 14, 16, 14)
            val head = Ui.horizontal(c)
            head.addView(badge, LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)).apply { rightMargin = Ui.dp(12) })
            val col = Ui.vertical(c)
            col.addView(title)
            col.addView(detail, Ui.lp(t = 1))
            head.addView(col, Ui.lp(0, Ui.WRAP, 1f, r = 8))
            head.addView(outcome, Ui.lp(Ui.WRAP, Ui.WRAP))
            head.addView(chevron, LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)).apply { leftMargin = Ui.dp(6) })
            root.addView(head)
            root.setOnClickListener { recipeId?.let(open) }
            // Étapes : sous un trait, comme dans la maquette.
            steps.addView(Ui.divider(c))
            steps.addView(stepsNote, Ui.lp(t = 8))
            root.addView(steps, Ui.lp(t = 12))
            root.addView(note, Ui.lp(t = 8))
        }

        fun bind(e: JournalEntry) {
            // Une recette encore au catalogue ouvre sa fiche ; les autres cartes ne se touchent pas.
            if (recipeId != e.recipeId || root.isClickable != (e.recipeId != null)) {
                recipeId = e.recipeId
                val tappable = e.recipeId != null
                root.isClickable = tappable
                root.isFocusable = tappable
                root.background = if (tappable) Ui.pressable(Ui.round(Ui.SURFACE, 20f), 20f) else Ui.round(Ui.SURFACE, 20f)
                Ui.show(chevron, tappable)
            }
            badge.icon.set(e.kind.icon)
            Ui.set(title, e.title)
            Ui.set(detail, e.detail)
            val (text, color) = when (e.outcome) {
                JournalOutcome.FINISHED -> (if (e.kind.feminine) tr("Terminée") else tr("Terminé")) to Ui.OK
                JournalOutcome.STOPPED -> (if (e.kind.feminine) tr("Arrêtée") else tr("Arrêté")) to Ui.TEXT2
                JournalOutcome.RUNNING -> tr("En cours") to Ui.ACCENT_INK
            }
            Ui.set(outcome, text)
            Ui.setColor(outcome, color)
            // Avec des étapes, la note va sous les étapes (dans le bloc du trait) ; sinon sous l'en-tête.
            val withSteps = e.steps.isNotEmpty()
            Ui.show(steps, withSteps)
            while (stepRows.size < e.steps.size) {
                val row = Ui.baselineRow(c)
                val name = Ui.ellipsize(Ui.text(c, "", 14f))
                val value = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2).apply { gravity = android.view.Gravity.END })
                row.addView(name, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
                row.addView(value, Ui.lp(0, Ui.WRAP, 1f))
                steps.addView(row, stepRows.size + 1, Ui.lp(t = if (stepRows.isEmpty()) 10 else 8))
                stepRows += name to value
            }
            while (stepRows.size > e.steps.size) {
                stepRows.removeAt(stepRows.size - 1)
                steps.removeViewAt(stepRows.size + 1)
            }
            e.steps.forEachIndexed { i, (n, v) ->
                Ui.set(stepRows[i].first, n)
                Ui.set(stepRows[i].second, v)
            }
            Ui.set(stepsNote, if (withSteps) e.note.orEmpty() else "")
            Ui.show(stepsNote, withSteps && e.note != null)
            Ui.set(note, if (!withSteps) e.note.orEmpty() else "")
            Ui.show(note, !withSteps && e.note != null)
        }
    }
}
