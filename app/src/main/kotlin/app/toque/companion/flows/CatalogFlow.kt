package app.toque.companion.flows

import app.toque.companion.link.CommandOutcome
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.RecipeActions
import app.toque.companion.ui.Route
import app.toque.companion.ui.Tab
import app.toque.companion.ui.screens.EnvoyeeState
import app.toque.companion.ui.screens.FicheState
import app.toque.companion.ui.screens.FicheStep
import app.toque.companion.ui.screens.IngredientItem
import app.toque.companion.ui.screens.RecettesState
import app.toque.companion.ui.screens.RecipeCard
import app.toque.companion.ui.screens.SendStatus
import app.toque.core.companion.CatalogFilter
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.RecipeSummary
import app.toque.core.companion.StateWire
import app.toque.core.companion.WaitingWire
import app.toque.core.companion.Wires
import app.toque.core.i18n.tr
import app.toque.core.recipes.RecipeChip

/**
 * Onglet Recettes branché sur la liaison : catalogue (recherche temporisée, filtres, pages à la
 * demande), fiche, envoi au robot, puis l'écran Envoyée qui suit la recette sur le robot (reçue,
 * retirée, lancée : « En direct » prend alors la main). Kotlin pur, fil principal.
 */
class CatalogFlow(
    private val link: RobotLink,
    private val nav: Navigator,
    private val renderList: (RecettesState) -> Unit,
    private val renderFiche: (FicheState) -> Unit,
    private val renderSent: (EnvoyeeState) -> Unit,
    private val schedule: Scheduler,
    private val clock: PhoneClock = PhoneClock(),
    /** Message bref (refus du robot) au-dessus des onglets. */
    private val notice: (String) -> Unit = {},
    /**
     * Recettes par page (le robot en donne 50 au plus) : 48, soit 24 lignes de la grille, deux ou trois
     * écrans. Moins de pages à demander à un robot lent ; leurs photos passent après celles des cartes à
     * l'écran ([PhotoQueue]), quelle que soit la taille de la page.
     */
    private val pageSize: Int = PAGE_SIZE,
    private val searchDelayMs: Long = 300,
    /** Temps laissé au robot pour ouvrir la recette lancée, avant de la dire retirée. */
    private val launchGraceMs: Long = 3_000,
    /** Nombre de personnes : envoyé ce délai après le dernier appui. */
    private val servingsDelayMs: Long = 300,
    /** Partage d'un texte (sujet, texte) vers l'appli choisie par l'utilisateur (sélecteur d'Android). */
    private val share: (subject: String, text: String) -> Unit = { _, _ -> },
) : RecipeActions, RobotLink.Listener {
    var list = RecettesState()
        private set
    var fiche: FicheState? = null
        private set
    var sent: EnvoyeeState? = null
        private set

    private var listGen = 0
    private var ficheGen = 0
    private var loaded = false
    private var cancelSearch: (() -> Unit)? = null
    private var cancelLaunchCheck: (() -> Unit)? = null
    private var state: StateWire? = null
    private var closed = false

    /**
     * Nombre de personnes d'une recette, du téléphone au robot (comme ManualTuner pour la cuisson) :
     * chaque appui se voit aussitôt, la demande part [servingsDelayMs] après le dernier avec le nombre
     * final, une seule à la fois ; le robot confirme, ou refuse et la fiche revient à [confirmed].
     * Gardé par recette : quitter la fiche ne l'annule pas.
     */
    private class ServingsSync(val id: String, var confirmed: RecipeDetail) {
        /** Le nombre voulu (dernier appui). */
        var wanted = confirmed.servings
        /** Nombre envoyé dont la réponse est attendue ; 0 : rien en route. */
        var inFlight = 0
        var cancelTimer: (() -> Unit)? = null
        /** À faire quand le robot a enregistré [wanted] : l'envoi de la recette. */
        var then: (() -> Unit)? = null
    }
    private val syncs = HashMap<String, ServingsSync>()
    /** Fiches à leur propre nombre de personnes : de quoi calculer les autres sans attendre le robot. */
    private val bases = object : LinkedHashMap<String, RecipeDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecipeDetail>?) = size > MAX_BASES
    }
    /** Dernière fiche du robot pour la fiche affichée. */
    private var detail: RecipeDetail? = null
    /** Recettes dont la fiche de base est en train d'arriver : une seule demande à la fois. */
    private val fetchingBases = HashSet<String>()
    /** Le robot a ignoré ?base=1 (robot plus ancien) : ne plus le demander avant la prochaine connexion. */
    private var baseUnsupported = false
    /** Le robot enregistre les favoris choisis ici (state.can.favorite) ; sinon l'étoile n'est qu'une étiquette. */
    private var favoritesSupported = false
    /** Favoris demandés au robot et pas encore confirmés : montrés tout de suite, remis si le robot refuse. */
    private val favPending = HashMap<String, Boolean>()
    /** Le robot enregistre les recettes écrites ici (state.can.editRecipes). */
    private var writeSupported = false
    /** L'éditeur (Éditeur, Étape) : ouvert par « Écrire une recette », « Modifier », « Ma version ». */
    var editor: EditFlow? = null
    private val unsubscribe = link.addListener(this)

    /** Fin de l'activité : un nombre de personnes pas encore envoyé part tout de suite (sans plus rien dessiner). */
    fun close() {
        unsubscribe()
        cancelSearch?.invoke()
        cancelLaunchCheck?.invoke()
        for (s in syncs.values.toList()) {
            s.then = null
            flush(s)
        }
        closed = true
    }

    // ------------------------------------------------------------------ catalogue

    /** Le catalogue paraît : première page si rien n'est chargé (ou après une erreur). */
    fun ensureLoaded() {
        if (!loaded || (list.items.isEmpty() && list.error != null)) reload()
    }

    private fun setList(s0: RecettesState) {
        // Étoiles en route vers le robot : leur nouvel état, même sur une page arrivée entre-temps.
        val items = if (favPending.isEmpty()) s0.items
            else s0.items.map { c -> favPending[c.id]?.takeIf { it != c.favorite }?.let { c.copy(favorite = it) } ?: c }
        val s = s0.copy(items = items, canFavorite = favoritesSupported, canWrite = writeSupported)
        list = s
        renderList(s)
    }

    override fun search(query: String) {
        if (query == list.query && loaded) return
        setList(list.copy(query = query))
        cancelSearch?.invoke()
        cancelSearch = schedule(searchDelayMs, Runnable {
            cancelSearch = null
            reload()
        })
    }

    override fun filter(filter: RecipeChip) {
        if (filter == list.filter && loaded) return
        setList(list.copy(filter = filter))
        cancelSearch?.invoke()
        reload()
    }

    /**
     * Recharge depuis la première page (recherche ou filtre changés, réessai). Les cartes déjà là
     * restent, estompées ([RecettesState.stale]), jusqu'à la réponse qui les remplace.
     */
    fun reload() {
        loaded = true
        val g = ++listGen
        setList(list.copy(stale = list.items.isNotEmpty(), hasMore = false, loading = true, error = null))
        fetch(g, 0)
    }

    override fun loadMore() {
        if (list.loading || !list.hasMore || list.error != null) return
        setList(list.copy(loading = true))
        fetch(listGen, list.items.size)
    }

    private fun fetch(g: Int, offset: Int) {
        val q = list.query.trim()
        val f = list.filter
        link.recipes(q, CatalogFilter.of(f), offset, pageSize, f.category) { r ->
            if (g != listGen) return@recipes
            r.onSuccess { page ->
                if (offset == 0) {
                    // Les pastilles du robot (première page) ; un robot plus ancien n'en donne pas : les quatre fixes.
                    val chips = page.filters?.mapNotNull { it.toChip() }?.takeIf { it.isNotEmpty() } ?: RecipeChip.FIXED
                    if (f !in chips) {
                        // La catégorie choisie a disparu du catalogue : retour à « Toutes ».
                        list = list.copy(filters = chips, filter = RecipeChip.ALL)
                        reload()
                        return@onSuccess
                    }
                    list = list.copy(filters = chips)
                }
                // Première page : elle remplace les cartes de la recherche précédente.
                val before = if (offset == 0) emptyList() else list.items
                val ids = HashSet<String>()
                for (c in before) ids.add(c.id)
                val items = before + page.items.filter { ids.add(it.id) }.map { card(it) }
                setList(list.copy(items = items, stale = false, hasMore = page.items.isNotEmpty() && offset + page.items.size < page.total,
                    loading = false, error = null))
            }.onFailure { e ->
                val out = CommandOutcome.of(e)
                if (out.code == "bad_request" && f !in RecipeChip.FIXED) {
                    // Robot plus ancien : il ne connaît que les quatre filtres fixes.
                    list = list.copy(filters = RecipeChip.FIXED, filter = RecipeChip.ALL)
                    reload()
                    return@onFailure
                }
                // Première page en échec : les cartes d'avant ne répondent pas à la recherche, elles s'en vont.
                setList(if (offset == 0) list.copy(items = emptyList(), stale = false, loading = false, error = out.message)
                    else list.copy(loading = false, error = out.message))
            }
        }
    }

    override fun retry() {
        if (nav.current == Route.FICHE) fiche?.let { openRecipe(it.id) }
        else if (list.items.isEmpty()) reload()
        else {
            setList(list.copy(error = null))
            loadMore()
        }
    }

    // ------------------------------------------------------------------ fiche

    override fun openRecipe(id: String) = openRecipe(id, navigate = true)

    /** [navigate] faux : la fiche est relue sans être montrée (l'éditeur est par-dessus). */
    private fun openRecipe(id: String, navigate: Boolean) {
        val c = list.items.firstOrNull { it.id == id }
        val g = ++ficheGen
        setFiche(withSend(FicheState(id, c?.title.orEmpty(), personal = c?.personal ?: false, category = c?.category.orEmpty(),
            stepCount = 0, favorite = c?.favorite ?: false, picture = c?.picture ?: false, durationMin = c?.durationMin, loading = true)))
        if (navigate && nav.current != Route.FICHE) nav.open(Route.FICHE)
        detail = null
        link.recipe(id) { r ->
            if (g != ficheGen) return@recipe
            r.onSuccess { d ->
                learn(d)
                detail = d
                // Un nombre choisi sur le téléphone pas encore confirmé reste affiché.
                val s = syncs[id]
                if (s != null && s.inFlight == 0) s.confirmed = d
                setFiche(present(if (s == null) fiche(d) else sheet(id, s.wanted, s.confirmed)))
            }.onFailure { e -> fiche?.let { setFiche(it.copy(loading = false, error = CommandOutcome.of(e).message)) } }
        }
    }

    /**
     * Nombre de personnes choisi sur la fiche : affiché aussitôt (calculé sur le téléphone avec le code
     * du robot quand la fiche de base est connue, sinon quantités en attente), puis enregistré par le
     * robot, qui reste la référence. Son refus (recette en cours…) ramène la dernière fiche confirmée.
     */
    override fun setServings(id: String, people: Int) {
        val f = fiche?.takeIf { it.id == id } ?: return
        if (f.loading || f.error != null || f.baseServings == null || f.servingsLocked || people == f.servings ||
            people !in f.minServings..f.maxServings) return
        val confirmed = syncs[id]?.confirmed ?: detail?.takeIf { it.id == id } ?: return
        // Hors ligne : rien ne changerait sur le robot, ne pas faire semblant.
        if (link.status.phase != LinkPhase.CONNECTED) {
            notice(tr("Le robot ne répond pas pour l'instant."))
            return
        }
        val s = syncs.getOrPut(id) { ServingsSync(id, confirmed) }
        s.wanted = people
        setFiche(present(sheet(id, people, s.confirmed)))
        s.cancelTimer?.invoke()
        s.cancelTimer = schedule(servingsDelayMs, Runnable {
            s.cancelTimer = null
            flush(s)
        })
    }

    /** La fiche pour [people] : celle du robot s'il l'a confirmée, sinon calculée, sinon la sienne en attente. */
    private fun sheet(id: String, people: Int, confirmed: RecipeDetail): FicheState {
        if (people == confirmed.servings) return fiche(confirmed)
        bases[id]?.let { b -> Wires.forServings(b, people)?.let { return fiche(it) } }
        return fiche(confirmed).copy(servings = people, quantitiesPending = true)
    }

    /** Fiche à son propre nombre gardée ; à un autre nombre, demandée au robot en arrière-plan (?base=1). */
    private fun learn(d: RecipeDetail) {
        if (d.baseServings <= 0) return
        if (d.servings == d.baseServings) {
            bases[d.id] = d
            return
        }
        if (baseUnsupported || bases.containsKey(d.id) || !fetchingBases.add(d.id)) return
        link.recipe(d.id, base = true) { r ->
            fetchingBases.remove(d.id)
            r.onSuccess { b ->
                // Robot plus ancien : il a répondu au nombre choisi.
                if (b.baseServings <= 0 || b.servings != b.baseServings) {
                    baseUnsupported = true
                    return@onSuccess
                }
                bases[b.id] = b
                // Un changement en attente de ses quantités : elles se calculent maintenant.
                val f = fiche
                val s = syncs[b.id]
                if (!closed && s != null && f != null && f.id == b.id && f.quantitiesPending) setFiche(present(sheet(b.id, s.wanted, s.confirmed)))
            }
        }
    }

    /** Envoie le nombre voulu s'il n'est pas celui du robot ; sinon c'est réglé. Une demande à la fois. */
    private fun flush(s: ServingsSync) {
        s.cancelTimer?.invoke()
        s.cancelTimer = null
        if (s.inFlight != 0 || syncs[s.id] !== s) return
        if (s.wanted == s.confirmed.servings) return settled(s)
        val n = s.wanted
        s.inFlight = n
        link.setServings(s.id, n) { r -> answered(s, n, r) }
    }

    private fun settled(s: ServingsSync) {
        syncs.remove(s.id)
        val then = s.then
        s.then = null
        then?.invoke()
    }

    private fun answered(s: ServingsSync, n: Int, r: Result<RecipeDetail>) {
        s.inFlight = 0
        if (syncs[s.id] !== s) return
        r.onSuccess { d ->
            s.confirmed = d
            // Fiche de base périmée (corrections changées sur le robot) : elle ne donne plus la réponse du
            // robot, elle est oubliée et learn la redemande (une fois par changement confirmé).
            if (n == s.wanted) bases[d.id]?.let { b -> if (Wires.forServings(b, d.servings) != d) bases.remove(d.id) }
            learn(d)
            if (closed) return@onSuccess flush(s)
            // La recette envoyée suit (l'état du robot le confirmera aussi).
            sent?.takeIf { it.id == s.id && d.servings > 0 && it.servings != d.servings }?.let { setSent(it.copy(servings = d.servings)) }
            if (n != s.wanted) {
                // Le nombre a encore changé : réponse dépassée, rien n'est dessiné ; le dernier part maintenant
                // ou à la fin de son délai.
                if (s.cancelTimer == null) flush(s)
                return@onSuccess
            }
            val f = fiche
            if (f != null && f.id == s.id) {
                detail = d
                // Normalement la fiche déjà affichée : rien à redessiner.
                val next = decorate(present(fiche(d)))
                if (next != f) setFiche(next)
            }
            settled(s)
        }.onFailure { e ->
            s.cancelTimer?.invoke()
            s.cancelTimer = null
            syncs.remove(s.id)
            if (closed) return@onFailure
            val f = fiche
            if (f != null && f.id == s.id) {
                detail = s.confirmed
                // Un envoi de la recette qui attendait ce nombre n'a pas lieu.
                val back = fiche(s.confirmed)
                setFiche(if (s.then != null) withSend(back) else present(back))
            }
            s.then = null
            notice(CommandOutcome.of(e).message)
        }
    }

    /** L'étoile telle qu'elle doit paraître : demande en route, et si le robot enregistre les favoris. */
    private fun decorate(f0: FicheState) = f0.copy(favorite = favPending[f0.id] ?: f0.favorite, canFavorite = favoritesSupported,
        canWrite = writeSupported)

    // ------------------------------------------------------------------ recettes écrites ici

    override fun writeRecipe() {
        if (writeSupported) editor?.startNew()
    }

    override fun editRecipe(id: String) {
        if (writeSupported) editor?.startEdit(id)
    }

    override fun myVersion(id: String) {
        if (writeSupported) editor?.startCopy(id)
    }

    /**
     * Le robot a enregistré la recette [id] : le catalogue est relu (s'il était chargé), et la fiche
     * affichée de cette recette aussi ; ouverte depuis l'éditeur, la fiche est celle de la recette enregistrée.
     */
    fun recipeSaved(id: String, leaving: Boolean) {
        bases.remove(id)
        if (loaded) reload()
        val f = fiche
        if (f != null && f.id == id) openRecipe(id, navigate = false)
        // L'éditeur fermé : la fiche de la recette enregistrée (celle d'où il était parti, ou ouverte maintenant).
        if (leaving && nav.current != Route.FICHE) openRecipe(id)
        else if (leaving && f?.id != id) openRecipe(id)
    }

    private fun setFiche(f0: FicheState) {
        val f = decorate(f0)
        fiche = f
        renderFiche(f)
    }

    // ------------------------------------------------------------------ favoris

    /**
     * Étoile touchée (fiche ou carte) : changée tout de suite ici, enregistrée par le robot (comme son
     * étoile) ; remise avec un message bref s'il refuse ou ne répond pas. Avec le filtre « Favoris »,
     * la liste est relue une fois le choix confirmé. Une demande à la fois par recette.
     */
    override fun setFavorite(id: String, favorite: Boolean) {
        if (!favoritesSupported || id in favPending) return
        if (link.status.phase != LinkPhase.CONNECTED) {
            notice(tr("Le robot ne répond pas pour l'instant."))
            return
        }
        favPending[id] = favorite
        showFavorite()
        link.setFavorite(id, favorite) { r ->
            favPending.remove(id)
            if (closed) return@setFavorite
            r.onSuccess { rep ->
                // Les fiches gardées suivent : la prochaine présentation ne remet pas l'ancienne étoile.
                detail?.takeIf { it.id == id }?.let { detail = it.copy(favorite = rep.favorite) }
                bases[id]?.let { bases[id] = it.copy(favorite = rep.favorite) }
                syncs[id]?.let { it.confirmed = it.confirmed.copy(favorite = rep.favorite) }
                setFavoriteShown(id, rep.favorite)
                if (list.filter.kind == RecipeChip.Kind.FAVORITES) reload()
            }.onFailure { e ->
                setFavoriteShown(id, !favorite)
                notice(tr("Favori non enregistré : %s", CommandOutcome.of(e).message))
            }
        }
    }

    private fun showFavorite() {
        setList(list)
        fiche?.let { setFiche(it) }
    }

    private fun setFavoriteShown(id: String, favorite: Boolean) {
        setList(list.copy(items = list.items.map { if (it.id == id && it.favorite != favorite) it.copy(favorite = favorite) else it }))
        fiche?.takeIf { it.id == id }?.let { setFiche(it.copy(favorite = favorite)) }
    }

    // ------------------------------------------------------------------ liste de courses

    /** La fiche telle qu'elle est affichée, au nombre choisi ; null tant que ses quantités ne sont pas connues. */
    private fun shownDetail(id: String): RecipeDetail? {
        val s = syncs[id]
        if (s != null) {
            if (s.wanted == s.confirmed.servings) return s.confirmed
            return bases[id]?.let { Wires.forServings(it, s.wanted) }
        }
        return detail?.takeIf { it.id == id }
    }

    /** « Partager la liste » : les ingrédients de la fiche affichée, en texte, vers l'appli choisie. */
    override fun shareIngredients(id: String) {
        val f = fiche?.takeIf { it.id == id && !it.loading && it.error == null } ?: return
        val d = shownDetail(id)
        if (d == null || f.quantitiesPending) {
            notice(tr("Les quantités arrivent du robot : réessayez dans un instant."))
            return
        }
        share(d.title, ShoppingList.text(d.copy(favorite = f.favorite)))
    }

    /** [f] remplace la fiche affichée : un envoi en cours de la même recette reste en cours. */
    private fun present(f: FicheState): FicheState {
        val cur = fiche
        return withSend(if (cur != null && cur.id == f.id && cur.send == SendStatus.SENDING) f.copy(send = SendStatus.SENDING) else f)
    }

    /**
     * Peut-on envoyer maintenant, ou changer le nombre de personnes ? Le robot décide ; l'état dit
     * d'avance ce qu'il refuserait (une recette guidée ouverte sur cette recette refuse le changement).
     */
    private fun withSend(f0: FicheState): FicheState {
        val locked = state?.session?.recipeId == f0.id
        val f = if (f0.servingsLocked == locked) f0 else f0.copy(servingsLocked = locked)
        if (f.send == SendStatus.SENDING) return f
        val s = state
        val online = link.status.phase == LinkPhase.CONNECTED && s != null
        return when {
            !online -> f.copy(send = SendStatus.BLOCKED, sendNote = tr("Le robot ne répond pas pour l'instant."))
            !s!!.can.sendRecipe.allowed -> f.copy(send = SendStatus.BLOCKED, sendNote = s.can.sendRecipe.reason.ifBlank { null })
            s.waiting != null && s.waiting!!.recipeId != f.id ->
                f.copy(send = SendStatus.READY, sendNote = tr("Elle remplacera « %s », qui attend sur le robot.", s.waiting!!.title))
            else -> f.copy(send = SendStatus.READY, sendNote = null)
        }
    }

    /**
     * « Envoyer au robot ». Un nombre de personnes pas encore enregistré part d'abord, sans attendre son
     * délai ; la recette suit quand le robot l'a confirmé (s'il le refuse, elle ne part pas).
     */
    override fun sendToRobot(id: String) {
        val f = fiche?.takeIf { it.id == id } ?: return
        if (f.send != SendStatus.READY) return
        setFiche(f.copy(send = SendStatus.SENDING))
        val s = syncs[id] ?: return transmit(id, f)
        s.then = { transmit(id, fiche?.takeIf { it.id == id } ?: f) }
        flush(s)
    }

    private fun transmit(id: String, f: FicheState) {
        val sentAt = clock.format("HH:mm", java.util.Locale.ROOT, clock.now())
        link.sendRecipe(id) { out ->
            val cur = fiche
            if (cur != null && cur.id == id) setFiche(withSend(cur.copy(send = SendStatus.READY)))
            if (!out.ok) {
                notice(out.message)
                return@sendRecipe
            }
            cancelLaunchCheck?.invoke()
            setSent(EnvoyeeState(f.id, f.title, f.personal, f.category, f.stepCount, f.picture, f.durationMin, f.servings,
                sentAt = sentAt, receivedAt = null))
            state?.let { track(it) }
            if (nav.current == Route.FICHE) nav.replace(Route.ENVOYEE) else nav.open(Route.ENVOYEE)
        }
    }

    // ------------------------------------------------------------------ recette envoyée

    private fun setSent(s: EnvoyeeState) {
        sent = s
        renderSent(s)
    }

    /** « Voir la recette » sur Attente : la recette qui attend sur le robot, dans l'écran Envoyée. */
    fun showWaiting() {
        val w = state?.waiting ?: return
        cancelLaunchCheck?.invoke()
        setSent(envoyee(w))
        nav.open(Route.ENVOYEE)
    }

    private fun envoyee(w: WaitingWire) = EnvoyeeState(w.recipeId, w.title, w.user, w.category, w.stepCount, w.hasImage,
        w.totalMinutes.takeIf { it > 0 }, w.servings.takeIf { it > 0 }, sentAt = clock.hhmm(w.sentAt), receivedAt = clock.hhmm(w.sentAt),
        sentBy = if (w.origin.self) null else w.origin.name.ifBlank { tr("un autre téléphone") })

    /** Retirer la recette qui attend (Envoyée ou Attente). Le robot décide ; son refus est montré. */
    fun withdraw() {
        val s = sent
        if (s != null) {
            if (s.withdrawing || s.removed) return
            setSent(s.copy(withdrawing = true))
        }
        link.removeWaiting { out ->
            val cur = sent
            if (!out.ok) {
                if (cur != null) setSent(cur.copy(withdrawing = false))
                notice(out.message)
                return@removeWaiting
            }
            if (cur != null) setSent(cur.copy(withdrawing = false, removed = true))
            if (nav.current == Route.ENVOYEE) nav.back()
        }
    }

    private fun track(s: StateWire) {
        val e = sent ?: return
        if (e.removed) return
        val w = s.waiting
        if (w != null && w.recipeId == e.id) {
            cancelLaunchCheck?.invoke()
            cancelLaunchCheck = null
            val at = clock.hhmm(w.sentAt)
            // Nombre de personnes changé depuis l'envoi (sur le robot ou un téléphone).
            val servings = w.servings.takeIf { it > 0 } ?: e.servings
            if (e.receivedAt != at || e.servings != servings) setSent(e.copy(receivedAt = at, servings = servings))
            return
        }
        // Elle n'attend plus : lancée sur le robot (une recette guidée la suit) ou retirée.
        if (s.session?.recipeId == e.id) return launched()
        if (e.receivedAt == null) return
        if (cancelLaunchCheck == null) cancelLaunchCheck = schedule(launchGraceMs, Runnable {
            cancelLaunchCheck = null
            val cur = sent ?: return@Runnable
            val st = state
            if (st?.session?.recipeId == cur.id) launched()
            else if (st?.waiting?.recipeId != cur.id) setSent(cur.copy(removed = true))
        })
    }

    private fun launched() {
        cancelLaunchCheck?.invoke()
        cancelLaunchCheck = null
        sent = null
        if (nav.current == Route.ENVOYEE) {
            nav.back()
            nav.select(Tab.LIVE)
        }
    }

    override fun onState(state: StateWire, receivedMono: Long) {
        this.state = state
        clock.observe(state)
        val fav = state.can.favorite.allowed
        val write = state.can.editRecipes.allowed
        if (fav != favoritesSupported || write != writeSupported) {
            favoritesSupported = fav
            writeSupported = write
            showFavorite()
        }
        track(state)
        fiche?.let { f -> withSend(f).takeIf { it != f }?.let { setFiche(it) } }
    }

    override fun onStatus(status: LinkStatus) {
        if (status.phase != LinkPhase.CONNECTED) {
            state = null
            // Le robot a pu être mis à jour entre-temps.
            baseUnsupported = false
        }
        if (status.phase == LinkPhase.NOT_PAIRED || status.phase == LinkPhase.REMOVED) {
            loaded = false
            listGen++
            list = RecettesState()
            sent = null
            favoritesSupported = false
            writeSupported = false
            favPending.clear()
            for (s in syncs.values) s.cancelTimer?.invoke()
            syncs.clear()
            bases.clear()
            fetchingBases.clear()
        }
        fiche?.let { f -> withSend(f).takeIf { it != f }?.let { setFiche(it) } }
    }

    companion object {
        /** Fiches de base gardées (les dernières recettes ouvertes). */
        private const val MAX_BASES = 30
        /** Recettes par page (voir le paramètre pageSize). */
        const val PAGE_SIZE = 48

        fun card(r: RecipeSummary) = RecipeCard(r.id, r.title, r.totalMinutes.takeIf { it > 0 }, r.category, r.user, r.favorite, r.hasImage)

        fun fiche(d: RecipeDetail) = FicheState(
            id = d.id, title = d.title, personal = d.user, category = d.category, stepCount = d.steps.size, favorite = d.favorite,
            picture = d.hasImage, durationMin = d.totalMinutes.takeIf { it > 0 }, servings = d.servings.takeIf { it > 0 },
            servingUnit = d.servingUnit,
            // Réglage du nombre de personnes : seulement si le robot le propose (pas un robot plus ancien).
            baseServings = d.baseServings.takeIf { it > 0 && d.servings > 0 && d.maxServings >= d.minServings && d.minServings > 0 },
            minServings = d.minServings, maxServings = d.maxServings, totalGrams = d.totalGrams, bowlTooFull = d.bowlTooFull,
            difficulty = when (d.difficulty) { 1 -> tr("Facile"); 2 -> tr("Moyen"); 3 -> tr("Difficile"); else -> null },
            ingredients = ingredients(d),
            steps = d.steps.map { st ->
                val m = st.machine
                val w = st.weigh
                FicheStep(st.text, when {
                    m != null -> m.summary
                    w != null -> tr("Pesée · %s", Fmt.grams(w.grams))
                    else -> tr("À la main")
                })
            },
        )

        /** Ingrédients d'une fiche : nom (et précision), quantité et unité, tels que le robot les donne. */
        fun ingredients(d: RecipeDetail): List<IngredientItem> = d.ingredients.flatMap { g ->
            g.items.map {
                val name = listOf(it.name, it.note).filter { n -> n.isNotBlank() }.joinToString(", ").ifEmpty { it.display }
                IngredientItem(name, listOf(it.qty, it.unit).filter { q -> q.isNotBlank() }.joinToString(" "))
            }
        }
    }
}
