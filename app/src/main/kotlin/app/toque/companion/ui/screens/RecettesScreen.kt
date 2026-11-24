package app.toque.companion.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ProgressBar
import app.toque.companion.ui.ChipRow
import app.toque.companion.ui.EmptyState
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.Icon
import app.toque.companion.ui.Link
import app.toque.companion.ui.LiveHeader
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.PictureSource
import app.toque.companion.ui.RecipeActions
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Thumbnail
import app.toque.companion.ui.Ui
import app.toque.core.i18n.tr
import app.toque.core.recipes.RecipeChip

/**
 * Catalogue du robot (racine de l'onglet Recettes). Le catalogue (environ 1700 recettes) arrive
 * page par page : [items] contient les recettes déjà chargées, dans l'ordre, et [hasMore] dit s'il
 * en reste ; l'écran appelle [RecipeActions.loadMore] quand la grille approche de la fin.
 */
data class RecettesState(
    val link: Link = Link.CONNECTED,
    /** Texte de recherche en cours (celui que l'écran a envoyé par [RecipeActions.search]). */
    val query: String = "",
    /** Pastille choisie : une de [filters]. */
    val filter: RecipeChip = RecipeChip.ALL,
    /**
     * Les pastilles du robot, dans son ordre (Toutes… « 30 min max », puis ses catégories) ; les
     * quatre fixes tant que sa liste n'est pas arrivée, ou chez un robot qui ne la donne pas.
     */
    val filters: List<RecipeChip> = RecipeChip.FIXED,
    val items: List<RecipeCard> = emptyList(),
    /** D'autres recettes restent à charger pour cette recherche et ce filtre. */
    val hasMore: Boolean = false,
    /** Une page est en cours de chargement (la première : écran de chargement ; les suivantes : bas de liste). */
    val loading: Boolean = false,
    /** Le chargement a échoué (« Le robot ne répond pas. ») ; null sinon. */
    val error: String? = null,
    /**
     * [items] sont encore ceux de la recherche ou du filtre d'avant : la réponse à la nouvelle est
     * attendue ([loading]). Cartes estompées, petit signe de chargement ; remplacées à la réponse.
     */
    val stale: Boolean = false,
    /** Le robot enregistre les favoris choisis ici : l'étoile des cartes est un bouton (sinon un simple repère). */
    val canFavorite: Boolean = false,
    /** Le robot enregistre les recettes écrites ici : « Écrire une recette » sous la pastille « Mes recettes ». */
    val canWrite: Boolean = false,
)

/** Une carte de la grille. */
data class RecipeCard(
    val id: String,
    val title: String,
    /** Durée totale en minutes, ou null si inconnue. */
    val durationMin: Int?,
    /** Catégorie (« Soupes »), ou vide. */
    val category: String,
    /** Recette créée sur le robot (pastille « Perso »). */
    val personal: Boolean = false,
    /** Favori sur le robot (étoile ; un bouton quand [RecettesState.canFavorite]). */
    val favorite: Boolean = false,
    /** La recette a une photo : la carte la demande à [PictureSource]. */
    val picture: Boolean = false,
)

/** « 35 min · Soupes » : la ligne grise d'une carte. */
internal fun cardDetail(durationMin: Int?, category: String): String =
    listOfNotNull(durationMin?.takeIf { it > 0 }?.let { Fmt.duration(it * 60) }, category.takeIf { it.isNotBlank() }).joinToString(" · ")

class RecettesScreen(c: Context, private val actions: RecipeActions, private val pictures: PictureSource, private val nav: Navigator) :
    Screen<RecettesState>(c) {
    override val tab = Tab.RECIPES
    private val header = LiveHeader(c, tr("Recettes"))
    private lateinit var field: EditText
    private lateinit var clear: View
    private lateinit var chips: ChipRow<RecipeChip>
    /** Fondu en haut seulement, comme les autres zones qui défilent ([Ui.scroll]). */
    private val grid = object : GridView(c) {
        override fun getBottomFadingEdgeStrength(): Float = 0f
    }
    private val adapter = Adapter()
    private val empty = EmptyState(c, Icon.Kind.SEARCH, tr("Réessayer")) { actions.retry() }
    private val footer = Ui.horizontal(c, Gravity.CENTER)
    private val footerSpinner = ProgressBar(c)
    private val footerText = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2), 2)
    private lateinit var loupe: View
    /** Nouvelle recherche en route, cartes d'avant estompées : un petit rond tourne à la place de la loupe. */
    private val staleSign = ProgressBar(c)
    /** « Écrire une recette », sous la pastille « Mes recettes ». */
    private lateinit var write: Ui.Button
    private var items: List<RecipeCard> = emptyList()
    /** Nombre de recettes chargées au dernier appel de loadMore (un seul appel par page). */
    private var requestedAt = -1
    private var binding = false

    override fun build(): View {
        val root = Ui.vertical(c)
        root.addView(header, Ui.lp())

        // Recherche : champ de 48 dp, loupe, effacement.
        val search = Ui.horizontal(c)
        search.background = Ui.round(Ui.SURFACE, 24f, Ui.STROKE, 1f)
        search.setPadding(Ui.dp(16), 0, Ui.dp(4), 0)
        search.minimumHeight = Ui.dp(48)
        loupe = Icon(c, Icon.Kind.SEARCH, Ui.TEXT2).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        search.addView(loupe, LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)).apply { rightMargin = Ui.dp(10) })
        staleSign.isIndeterminate = true
        staleSign.indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT_INK)
        staleSign.contentDescription = tr("Chargement…")
        staleSign.visibility = View.GONE
        search.addView(staleSign, LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)).apply { rightMargin = Ui.dp(10) })
        field = EditText(c).apply {
            background = null
            setPadding(0, 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT2)
            hint = tr("Recette, ingrédient…")
            contentDescription = tr("Rechercher une recette")
            typeface = Ui.REGULAR
            includeFontPadding = false
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            minHeight = Ui.dp(44)
            Ui.tintField(this)
            setOnEditorActionListener { v, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) {
                    (c.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Ui.show(clear, !s.isNullOrEmpty())
                    if (!binding) actions.search(s?.toString().orEmpty())
                }
            })
        }
        search.addView(field, Ui.lp(0, Ui.WRAP, 1f))
        clear = Ui.iconButton(c, Icon.Kind.CLOSE, tr("Effacer la recherche"), visible = 32, iconSize = 18, style = Ui.Style.GHOST) {
            field.setText("")
        }
        clear.background = Ui.pressable(ColorDrawable(Color.TRANSPARENT), 24f)
        clear.visibility = View.GONE
        search.addView(clear)
        root.addView(search, Ui.lp(l = 20, t = 10, r = 20))

        // Une ligne qui défile, comme sur le robot : une trentaine de pastilles.
        chips = ChipRow(c) { if (it != state?.filter) actions.filter(it) }
        chips.setPadding(Ui.dp(20), 0, Ui.dp(20), 0)
        root.addView(chips, Ui.lp(t = 12))
        write = Ui.button(c, tr("Écrire une recette"), Ui.Style.OUTLINE, Icon.Kind.EDIT, height = 44, textSize = 15f, iconSize = 18) {
            actions.writeRecipe()
        }
        Ui.ellipsize(write.label, 2)
        write.visibility = View.GONE
        root.addView(write, Ui.lp(Ui.WRAP, Ui.WRAP, l = 20, t = 8, r = 20))

        // Grille à deux colonnes, cartes recyclées (catalogue de 1700 recettes).
        grid.numColumns = 2
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.horizontalSpacing = Ui.dp(12)
        grid.verticalSpacing = Ui.dp(12)
        grid.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(12))
        grid.clipToPadding = false
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        grid.isVerticalScrollBarEnabled = false
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        Ui.topFade(grid)
        grid.adapter = adapter
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) = nearEnd(first + visible, total)
        })
        val content = FrameLayout(c)
        content.addView(grid, Ui.flp())
        content.addView(empty, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.TOP))
        root.addView(content, Ui.lp(Ui.MATCH, 0, 1f))

        // Bas de liste : page suivante en chargement, ou erreur (toucher pour réessayer).
        footerSpinner.isIndeterminate = true
        footerSpinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT_INK)
        footer.addView(footerSpinner, LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)).apply { rightMargin = Ui.dp(10) })
        footer.addView(footerText, Ui.lp(Ui.WRAP, Ui.WRAP))
        footer.minimumHeight = Ui.dp(Ui.TOUCH)
        footer.setOnClickListener { if (state?.error != null) actions.retry() }
        root.addView(footer, Ui.lp(l = 20, r = 20))
        return root
    }

    /** La grille montre ses dernières cartes : demander la page suivante (une fois par page). */
    private fun nearEnd(lastVisible: Int, total: Int) {
        val s = state ?: return
        if (!s.hasMore || s.loading || s.error != null || total == 0) return
        if (lastVisible < total - LOOKAHEAD || requestedAt == total) return
        requestedAt = total
        actions.loadMore()
    }

    override fun bind(s: RecettesState, old: RecettesState?) {
        header.set(s.link)
        if (field.text.toString() != s.query) {
            binding = true
            field.setText(s.query)
            field.setSelection(s.query.length)
            binding = false
        }
        Ui.show(clear, s.query.isNotEmpty())
        // Textes des pastilles fixes traduits ici (mêmes clés que le robot) ; catégories telles quelles.
        chips.set(s.filters.map { it to it.label }, s.filter)
        Ui.show(write, s.canWrite && s.filter.kind == RecipeChip.Kind.USER)
        val newSearch = old != null && (old.query != s.query || old.filter != s.filter)
        // Réponse à une nouvelle recherche : ses cartes remplacent les anciennes, retour en haut.
        val replaced = old != null && old.stale && !s.stale
        if (s.items != items || s.canFavorite != old?.canFavorite) {
            if (s.items.size < items.size || newSearch || replaced) requestedAt = -1
            items = s.items
            adapter.notifyDataSetChanged()
        }
        if (replaced) grid.setSelection(0)
        grid.alpha = if (s.stale) STALE_ALPHA else 1f
        val waiting = s.stale && s.items.isNotEmpty()
        Ui.show(staleSign, waiting)
        Ui.show(loupe, !waiting)

        val none = s.items.isEmpty()
        Ui.show(grid, !none)
        Ui.show(empty, none)
        if (none) when {
            s.loading -> empty.set(tr("Chargement des recettes…"), "", loading = true)
            s.error != null -> empty.set(tr("Catalogue indisponible"), s.error, withButton = true)
            s.query.isNotBlank() -> empty.set(tr("Aucune recette pour « %s »", s.query.trim()), tr("Essayez un autre mot ou un ingrédient."))
            s.filter.kind == RecipeChip.Kind.FAVORITES -> empty.set(tr("Aucun favori"),
                if (s.canFavorite) tr("Touchez l'étoile d'une recette, ici ou sur le robot.")
                else tr("Les favoris se choisissent sur le robot, avec l'étoile d'une recette."))
            s.filter.byUsage -> empty.set(tr("Aucune recette cuisinée"), tr("Les recettes cuisinées sur le robot s'afficheront ici."))
            s.filter.kind == RecipeChip.Kind.USER -> empty.set(tr("Aucune recette perso"),
                if (s.canWrite) tr("Écrivez-la ici, ou faites « Ma version » d'une recette Lidl.")
                else tr("Créez-la sur le robot : « Nouvelle recette », ou le crayon d'une recette Lidl."))
            s.filter != RecipeChip.ALL -> empty.set(tr("Aucune recette ne correspond"), tr("Choisissez un autre filtre."))
            else -> empty.set(tr("Aucune recette"), tr("Le catalogue du robot est vide : synchronisez les recettes Lidl sur le robot."))
        }
        val foot = !none && !s.stale && (s.loading || s.error != null)
        Ui.show(footer, foot)
        if (foot) {
            Ui.show(footerSpinner, s.loading)
            Ui.set(footerText, if (s.loading) tr("Chargement…") else tr("%s Touchez pour réessayer.", s.error.orEmpty()))
            footer.isClickable = !s.loading
        }
        // La grille appelle onScroll après chaque mise en page (nouvelles cartes, grille pas pleine) ;
        // ici, le cas où l'état change sans nouvelle mise en page (fin du chargement, erreur levée).
        if (!none && grid.childCount > 0) nearEnd(grid.firstVisiblePosition + grid.childCount, items.size)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? Card) ?: Card(c)
            card.bind(items[position])
            return card
        }
    }

    /** Une carte : vignette de 88 dp (photo ou initiales), pastille « Perso », étoile, titre, détail. */
    private inner class Card(c: Context) : LinearLayout(c) {
        private val thumb = Thumbnail(c, 20f, 34f, topOnly = true)
        // Posés sur la photo : voile sombre et texte clair, quel que soit le thème.
        private val badge = Ui.text(c, tr("Perso"), 12f, Ui.ON_PHOTO, Ui.MEDIUM).apply {
            setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(8), Ui.dp(2))
            background = Ui.round(Ui.PHOTO_VEIL, 10f)
            tag = Ui.FIXED
        }
        private val star = Icon(c, Icon.Kind.STAR_FILLED, Ui.ACCENT).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        /** Zone de 48 dp en haut à droite de la vignette : rond sombre et étoile ; un bouton quand le robot le permet. */
        private val starBox = FrameLayout(c).apply { tag = Ui.FIXED }
        private var favorite = false
        private val title = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT, Ui.MEDIUM, lineHeight = 20f), 2).apply { minLines = 2 }
        private val detail = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2))
        private var id: String? = null

        init {
            orientation = VERTICAL
            background = Ui.pressable(Ui.round(Ui.SURFACE, 20f), 20f)
            thumb.addView(badge, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { setMargins(Ui.dp(8), Ui.dp(8), 0, 0) })
            starBox.addView(star, Ui.flp(Ui.dp(20), Ui.dp(20), Gravity.CENTER))
            starBox.setOnClickListener { id?.let { actions.setFavorite(it, !favorite) } }
            // 48 dp au toucher, collé au coin : l'étoile reste à 14 dp du bord comme avant.
            thumb.addView(starBox, Ui.flp(Ui.dp(Ui.TOUCH), Ui.dp(Ui.TOUCH), Gravity.TOP or Gravity.END))
            addView(thumb, LayoutParams(Ui.MATCH, Ui.dp(88)))
            val text = Ui.vertical(c)
            Ui.pad(text, 12, 10, 12, 12)
            text.addView(title)
            text.addView(detail, Ui.lp(t = 4))
            addView(text, Ui.lp())
            isClickable = true
            isFocusable = true
            setOnClickListener { id?.let { actions.openRecipe(it) } }
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
        }

        fun bind(r: RecipeCard) {
            id = r.id
            thumb.bind(r.id, r.title, r.picture, pictures, 180)
            Ui.show(badge, r.personal)
            favorite = r.favorite
            val editable = state?.canFavorite == true
            // Bouton : toujours là, rempli ou creux sur un rond sombre ; sinon le repère d'avant, seulement pour un favori.
            Ui.show(starBox, editable || r.favorite)
            star.set(if (r.favorite) Icon.Kind.STAR_FILLED else Icon.Kind.STAR)
            star.color = if (r.favorite) Ui.ACCENT else Ui.ON_PHOTO
            starBox.background = if (editable) Ui.inset(Ui.round(Ui.PHOTO_VEIL, 16f), 8f, 8f) else null
            starBox.isClickable = editable
            starBox.isFocusable = editable
            starBox.contentDescription = when {
                editable && r.favorite -> tr("Retirer des favoris")
                editable -> tr("Ajouter aux favoris")
                else -> tr("Favori")
            }
            starBox.importantForAccessibility = if (editable || r.favorite) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
            Ui.set(title, r.title)
            Ui.set(detail, cardDetail(r.durationMin, r.category))
            contentDescription = null
        }
    }

    companion object {
        /**
         * Page suivante demandée quand il reste moins de ce nombre de cartes sous l'écran : 8 lignes,
         * le tiers d'une page de 48 ([app.toque.companion.flows.CatalogFlow.PAGE_SIZE]), deux écrans
         * d'avance pour un robot lent à répondre.
         */
        const val LOOKAHEAD = 16
        /** Cartes de la recherche d'avant, en attendant la réponse. */
        const val STALE_ALPHA = 0.45f
    }
}
