package app.toque.companion.link

import app.toque.core.companion.AdjustCommand
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CatalogSearch
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.ImageData
import app.toque.core.companion.RobotInfo
import app.toque.core.companion.ServingsChange
import app.toque.core.companion.ServingsReply
import app.toque.core.machine.Clock
import app.toque.core.machine.MachineLoop
import app.toque.core.machine.SimBoard
import app.toque.core.machine.SimTransport
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Direction
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.corrected
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.Weigh
import app.toque.core.recipes.forServings
import java.net.InetAddress

/**
 * Un robot sur la JVM : le vrai MachineController sur la base simulée, le vrai hub et son serveur
 * HTTP sur une adresse locale, branchés comme dans la démo (core/src/demo) et sur le robot.
 */
class SimRobot {
    private val clock = object : Clock {
        private val start = System.nanoTime()
        override fun nowMs(): Long = (System.nanoTime() - start) / 1_000_000L + 1_000_000L
    }
    val board = SimBoard(clock)
    @Volatile var snapshot = Snapshot()
        private set
    lateinit var loop: MachineLoop
        private set

    val recipe = Recipe(
        id = "perso-veloute-de-potimarron", title = "Velouté de potimarron", servings = 4, totalMinutes = 35,
        ingredients = listOf(IngredientGroup(items = listOf(Ingredient("1", "", "oignon"), Ingredient("20", "g", "Beurre"),
            Ingredient("600", "g", "Potimarron"), Ingredient("0,5", "l", "lait")))),
        steps = listOf(
            Step("Couper le potimarron et l'oignon en dés."),
            Step("Mettre le beurre dans le bol.", weigh = Weigh("Beurre", 20)),
            Step("Faire revenir l'oignon.", StepMachine(StepProgram.MANUAL, 180, 1, 100)),
        ),
    )
    /** Le velouté et 59 recettes inventées : 60 au catalogue (pages, recherche, favoris). */
    val recipes: List<Recipe> = listOf(recipe) + (1..59).map { i ->
        val (cat, name) = KINDS[i % KINDS.size]
        Recipe(id = "essai-$i", title = "$name n° $i", category = cat, servings = 2 + i % 4, totalMinutes = 10 + i,
            favorite = i % 7 == 0, difficulty = 1 + i % 3, image = if (i % 3 == 0) "p.png" else "",
            steps = listOf(Step("Mettre les ingrédients dans le bol."), Step("Mélanger.", StepMachine(StepProgram.MANUAL, 30, 3))))
    }
    private val byId = recipes.associateBy { it.id }
    /** Nombre de personnes choisi par recette (RecipeAdjustments.servings sur le robot). */
    val servings = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Nombres demandés par POST …/servings, dans l'ordre. */
    val servingsRequests = java.util.concurrent.CopyOnWriteArrayList<Int>()
    /** Demandes GET …?base=1 servies. */
    val baseRequests = java.util.concurrent.atomic.AtomicInteger()
    /** Réponse de POST …/servings retardée (robot lent), en ms. */
    @Volatile var servingsDelayMs = 0L
    /** Refus du robot pour tout changement du nombre de personnes, ou null. */
    @Volatile var servingsRefusal: CommandResult? = null
    /** Réponse de GET …?base=1 retardée, en ms. */
    @Volatile var baseDelayMs = 0L
    /** Corrections de l'utilisateur par recette (faites sur le robot, sans le nombre de personnes). */
    val corrections = java.util.concurrent.ConcurrentHashMap<String, RecipeAdjustments>()
    /** Recettes perso écrites depuis un téléphone (créées, copiées, modifiées), par-dessus le catalogue. */
    val written = java.util.concurrent.ConcurrentHashMap<String, Recipe>()
    /** Recette ouverte dans l'« éditeur » du robot, ou null. */
    @Volatile var editorOpen: String? = null
    /** Recettes d'un serveur Mealie relié au robot ([addMealie]), en lecture seule comme sur le robot. */
    val mealie = java.util.concurrent.ConcurrentHashMap<String, Recipe>()
    /** La recette telle qu'enregistrée. */
    fun stored(id: String): Recipe? = written[id] ?: mealie[id] ?: byId[id]

    /**
     * Relie un « serveur Mealie » qui apporte une recette inventée : elle entre au catalogue (la pastille
     * « Mealie » du robot apparaît) et sa fiche dit d'où elle vient. Rend la recette.
     */
    fun addMealie(): Recipe {
        val r = Recipe(id = "mealie-0b1c2d3e", title = "Gâteau du verger", category = "Desserts", servings = 6, totalMinutes = 50,
            source = app.toque.core.recipes.RecipeSource.MEALIE,
            ingredients = listOf(IngredientGroup(items = listOf(Ingredient("3", "", "pommes"), Ingredient("120", "g", "Farine")))),
            steps = listOf(Step("Mettre la farine dans le bol.", weigh = Weigh("Farine", 120)), Step("Mélanger.", StepMachine(StepProgram.MANUAL, 30, 3))))
        mealie[r.id] = r
        index = SearchIndex(recipes + r)
        return r
    }
    /** La recette à son propre nombre, corrections comprises. */
    fun base(id: String): Recipe? = stored(id)?.let { r -> corrections[id]?.let { r.corrected(it) } ?: r }
    /** Catalogue servi ; remplaçable (une catégorie qui disparaît). */
    @Volatile var index = SearchIndex(recipes)

    /** Favoris choisis (depuis un téléphone) par recette, par-dessus ceux du catalogue. */
    val favorites = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Favoris demandés, dans l'ordre (« id:true »). */
    val favoriteRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Refus du robot pour tout favori, ou null. */
    @Volatile var favoriteRefusal: CommandResult? = null
    /** Robot plus ancien : ni favoris ni arrêt de la recette depuis le téléphone. */
    @Volatile var older = false
    /** Une recette guidée est ouverte sur l'« écran » du robot ([openRecipe]). */
    @Volatile var cookOpen = false
    /** Demandes « Arrêter la recette » reçues. */
    val stopRecipeRequests = java.util.concurrent.atomic.AtomicInteger()

    /** Le robot n'est pas libre pour changer d'apparence (autre écran ouvert, programme…). */
    @Volatile var lookBusy = false
    /** Demandes d'apparence reçues (« accent:theme »). */
    val lookRequests: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** Robot de lot A : il écrit les recettes du téléphone, sans les réglages d'étape. */
    @Volatile var noSettings = false

    /** Robot d'avant la roue : il ne prend que les dix couleurs (pas de robot.anyAccent). */
    @Volatile var tenColoursOnly = false

    private val host = object : CompanionHost {
        override val setsAppearance get() = !older
        override val setsAnyAccent get() = !older && !tenColoursOnly
        // Comme MainActivity.appearanceFromPhone : refus si occupé, sinon l'interface refaite redit son apparence.
        override fun setAppearance(accent: String?, theme: String?): CommandResult {
            lookRequests += "$accent:$theme"
            if (lookBusy) return CommandResult.refuse("busy", "Le robot n'est pas sur son écran d'accueil : revenez-y sur le robot, puis réessayez")
            val now = hub.robotInfo()
            hub.updateRobot(now.copy(accent = accent ?: now.accent, theme = theme ?: now.theme))
            return CommandResult.OK
        }
        override val setsFavorites get() = !older
        override fun setFavorite(recipeId: String, favorite: Boolean): CommandResult {
            favoriteRequests += "$recipeId:$favorite"
            favoriteRefusal?.let { return it }
            if (byId[recipeId] == null) return CommandResult.refuse("not_found", "Recette introuvable")
            favorites[recipeId] = favorite
            index.entry(recipeId)?.favorite = favorite
            return CommandResult.OK
        }
        override val writesRecipes get() = !older
        override val writesMachineSteps get() = !older && !noSettings
        override fun storedRecipe(id: String): Recipe? = stored(id)
        // Comme RobotHost : d'un bloc, la recette enregistrée et l'éditeur du robot, puis l'écriture.
        @Synchronized override fun writeRecipe(id: String, decide: (stored: Recipe?, inEditor: Boolean) -> app.toque.core.companion.RecipeWrite): app.toque.core.companion.RecipeWrite {
            val w = decide(stored(id), editorOpen == id)
            w.saved?.let { written[it.id] = it }
            return w
        }
        override val stopsRecipes get() = !older
        // Comme l'écran de la recette guidée : le programme lancé s'arrête, la session se clôt.
        override fun stopRecipe(): CommandResult {
            stopRecipeRequests.incrementAndGet()
            if (!cookOpen) return CommandResult.refuse("no_recipe", "Aucune recette en cours sur le robot")
            cookOpen = false
            loop.call { c -> if (c.snapshot().jobActive) c.stop() }
            hub.endSession(false)
            return CommandResult.OK
        }
        override fun pause() = CommandResult.of(loop.call { it.pause() })
        override fun stop() = CommandResult.of(loop.call { it.stop() })
        override fun resume(stillAllowed: () -> Boolean): CommandResult = loop.call { c ->
            if (!stillAllowed()) CommandResult.refuse("changed", "État changé : reprenez sur le robot") else CommandResult.of(c.resume(false))
        }
        override fun adjust(prepare: () -> AdjustCommand): CommandResult = loop.call { c ->
            val a = prepare()
            a.refusal ?: CommandResult.of(c.adjust(a.speed, a.temperatureLevel,
                a.reverse?.let { if (it) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE }, a.totalMs, false))
        }
        override fun tare(): CommandResult {
            loop.post { it.tare() }
            return CommandResult.OK
        }
        // Le bouton OK du robot (MachineClient.clearFinished).
        override fun clear(): CommandResult {
            loop.call { it.clearFinished() }
            return CommandResult.OK
        }
        override fun start(prepare: () -> app.toque.core.companion.StartCommand): CommandResult = loop.call { c ->
            val a = prepare()
            a.refusal ?: CommandResult.of(c.startJob(a.spec!!))
        }
        override fun catalog(query: CatalogQuery): CatalogPageWire = CatalogSearch.page(index, query, emptyList(), emptyList())
        override fun recipe(id: String): Recipe? = base(id)?.let { r -> servings[id]?.let { r.forServings(it) } ?: r }
            ?.let { r -> favorites[id]?.let { r.copy(favorite = it) } ?: r }
        override fun baseRecipe(id: String): Recipe? {
            baseRequests.incrementAndGet()
            if (baseDelayMs > 0) Thread.sleep(baseDelayMs)
            return base(id)
        }
        override fun baseServings(recipeId: String): Int = stored(recipeId)?.servings ?: 0
        // Comme RobotHost : refus si la recette est en cours, règles de ServingsChange, choix gardé par recette.
        override fun setServings(recipeId: String, people: Int, stillAllowed: () -> Boolean): ServingsReply {
            servingsRequests += people
            if (servingsDelayMs > 0) Thread.sleep(servingsDelayMs)
            servingsRefusal?.let { return ServingsReply(refusal = it) }
            if (!stillAllowed()) return ServingsReply(refusal = ServingsChange.cooking())
            val r = stored(recipeId)
            ServingsChange.check(r, people)?.let { return ServingsReply(refusal = it) }
            if (people == r!!.servings) servings.remove(recipeId) else servings[recipeId] = people
            return ServingsReply(recipe(recipeId), r.servings)
        }
        override fun image(recipeId: String): ImageData? =
            if (byId[recipeId]?.image?.isNotEmpty() == true) ImageData(byteArrayOf(1, 2, 3), "image/png") else null
    }

    val hub: CompanionHub = CompanionHub(host)

    fun start(port: Int = 0, bind: String = "127.0.0.1"): SimRobot {
        hub.updateRobot(RobotInfo("Monsieur Cuisine Connect", "0.7.0", "fr"))
        hub.setRemoteStart(true)
        loop = MachineLoop({ SimTransport(board) }, clock, onSnapshot = { s ->
            snapshot = s
            hub.updateMachine(s)
        }, onEvent = { hub.onMachineEvent(it) })
        loop.start()
        hub.start(port, InetAddress.getByName(bind))
        return this
    }

    /** Arrête le serveur seul (le robot « disparaît » du réseau), puis le relance ailleurs avec [listen]. */
    fun listen(port: Int, bind: String) {
        hub.stop()
        hub.start(port, InetAddress.getByName(bind))
    }

    val port: Int get() = hub.port

    /** Ouvre le velouté en recette guidée sur le robot (étape [current], ce que le robot attend). */
    fun openRecipe(current: Int = 0, waitingFor: app.toque.core.companion.WaitingFor = app.toque.core.companion.WaitingFor.HAND) {
        cookOpen = true
        hub.updateSession(app.toque.core.companion.CookSession.of(recipe, current, waitingFor = waitingFor))
    }

    companion object {
        private val KINDS = listOf("Soupes" to "Soupe de saison", "Desserts" to "Crème vanillée", "Plats" to "Risotto du marché",
            "Pâtes" to "Pâte à pain")
    }

    fun shutdown() {
        try {
            hub.stop()
        } finally {
            loop.shutdown()
        }
    }
}
