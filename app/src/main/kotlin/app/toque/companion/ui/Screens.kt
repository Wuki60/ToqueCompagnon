package app.toque.companion.ui

import android.content.Context
import app.toque.companion.ui.screens.AppairageScreen
import app.toque.companion.ui.screens.AttenteScreen
import app.toque.companion.ui.screens.CoursesActions
import app.toque.companion.ui.screens.CoursesScreen
import app.toque.companion.ui.screens.DiagnosticScreen
import app.toque.companion.ui.screens.EditeurScreen
import app.toque.companion.ui.screens.EnvoyeeScreen
import app.toque.companion.ui.screens.EtapeScreen
import app.toque.companion.ui.screens.EtapesScreen
import app.toque.companion.ui.screens.IncidentScreen
import app.toque.companion.ui.screens.FicheScreen
import app.toque.companion.ui.screens.FinScreen
import app.toque.companion.ui.screens.JournalScreen
import app.toque.companion.ui.screens.MainScreen
import app.toque.companion.ui.screens.ManuelScreen
import app.toque.companion.ui.screens.MonReglageScreen
import app.toque.companion.ui.screens.PauseScreen
import app.toque.companion.ui.screens.PeseeScreen
import app.toque.companion.ui.screens.ProgrammeScreen
import app.toque.companion.ui.screens.RecettesScreen
import app.toque.companion.ui.screens.ReposScreen
import app.toque.companion.ui.screens.RepriseScreen
import app.toque.companion.ui.screens.RobotScreen

/**
 * Tous les écrans de l'appli. [tab] : onglet de l'écran ; [root] : écran de départ de cet onglet ;
 * [fullScreen] : affiché sans la barre d'onglets (voir Navigator.showPairing).
 * Ajouter un écran : une valeur ici, une propriété dans [Screens] et une ligne dans [Screens.get].
 */
enum class Route(val tab: Tab, val root: Boolean = false, val fullScreen: Boolean = false) {
    // En direct (la racine change avec l'état du robot, voir Navigator.showLive).
    REPOS(Tab.LIVE, root = true),
    MAIN(Tab.LIVE),
    PESEE(Tab.LIVE),
    PAUSE(Tab.LIVE),
    REPRISE(Tab.LIVE),
    MANUEL(Tab.LIVE),
    PROGRAMME(Tab.LIVE),
    ATTENTE(Tab.LIVE),
    /** Programme terminé ou arrêté, encore affiché sur le robot avec son bouton OK. */
    FIN(Tab.LIVE),
    ETAPES(Tab.LIVE),
    /** Robot injoignable, connexion, alerte machine, base moteur pas prête. */
    INCIDENT(Tab.LIVE),

    // Recettes : catalogue, fiche, recette envoyée qui attend sur le robot.
    RECETTES(Tab.RECIPES, root = true),
    FICHE(Tab.RECIPES),
    ENVOYEE(Tab.RECIPES),
    /** Écrire ou modifier une de ses recettes, puis une de ses étapes. */
    EDITEUR(Tab.RECIPES),
    ETAPE(Tab.RECIPES),
    /** « Mon réglage » d'une étape d'une recette reçue (Mealie), sans la copier. */
    REGLAGE(Tab.RECIPES),

    /** La liste de courses, gardée sur le téléphone. */
    COURSES(Tab.SHOP, root = true),

    JOURNAL(Tab.LOG, root = true),
    ROBOT(Tab.ROBOT, root = true),
    /** Diagnostic des alertes (ligne discrète en bas de l'onglet Robot). */
    DIAGNOSTIC(Tab.ROBOT),

    // Plein écran tant que le téléphone n'est pas appairé (Navigator.showPairing).
    APPAIRAGE(Tab.ROBOT, fullScreen = true),
}

/**
 * Registre des écrans : une instance par écran, construite à la première utilisation et gardée.
 * Le code qui suit le robot pousse les états avec `screens.main.render(MainState(…))`, puis
 * choisit l'écran avec `nav.showLive(Route.MAIN)`.
 *
 * Nouvelles couleurs (couleur du robot changée) : [markStale] puis [rebuildStale] remplacent chaque
 * écran par un neuf, construit avec les nouvelles couleurs, qui reprend l'état de l'ancien
 * ([Screen.takeOver]). Toujours lire un écran par ces propriétés, sans le garder : l'instance change.
 */
class Screens(
    private val c: Context,
    private val actions: LiveActions,
    private val nav: Navigator,
    private val recipes: RecipeActions = object : RecipeActions {},
    private val pictures: PictureSource = PictureSource.NONE,
    private val journalActions: JournalActions = object : JournalActions {},
    private val robotActions: RobotActions = object : RobotActions {},
    private val pairing: PairingActions = object : PairingActions {},
    private val edit: EditActions = object : EditActions {},
    private val coursesActions: CoursesActions = object : CoursesActions {},
    private val ownStep: OwnStepActions = object : OwnStepActions {},
) {
    private val built = java.util.EnumMap<Route, Screen<*>>(Route::class.java)
    /** Écrans construits avec des couleurs qui ne sont plus celles de [Ui]. */
    private val stale = java.util.EnumSet.noneOf(Route::class.java)

    val repos get() = get(Route.REPOS) as ReposScreen
    val main get() = get(Route.MAIN) as MainScreen
    val pesee get() = get(Route.PESEE) as PeseeScreen
    val pause get() = get(Route.PAUSE) as PauseScreen
    val reprise get() = get(Route.REPRISE) as RepriseScreen
    val manuel get() = get(Route.MANUEL) as ManuelScreen
    val programme get() = get(Route.PROGRAMME) as ProgrammeScreen
    val attente get() = get(Route.ATTENTE) as AttenteScreen
    val fin get() = get(Route.FIN) as FinScreen
    val etapes get() = get(Route.ETAPES) as EtapesScreen
    val incident get() = get(Route.INCIDENT) as IncidentScreen
    val recettes get() = get(Route.RECETTES) as RecettesScreen
    val fiche get() = get(Route.FICHE) as FicheScreen
    val envoyee get() = get(Route.ENVOYEE) as EnvoyeeScreen
    val journal get() = get(Route.JOURNAL) as JournalScreen
    val robot get() = get(Route.ROBOT) as RobotScreen
    val appairage get() = get(Route.APPAIRAGE) as AppairageScreen
    val diagnostic get() = get(Route.DIAGNOSTIC) as DiagnosticScreen
    val editeur get() = get(Route.EDITEUR) as EditeurScreen
    val etape get() = get(Route.ETAPE) as EtapeScreen
    val reglage get() = get(Route.REGLAGE) as MonReglageScreen
    val courses get() = get(Route.COURSES) as CoursesScreen

    operator fun get(route: Route): Screen<*> = built.getOrPut(route) { create(route) }

    private fun create(route: Route): Screen<*> = when (route) {
        Route.REPOS -> ReposScreen(c, actions, nav)
        Route.MAIN -> MainScreen(c, actions, nav)
        Route.PESEE -> PeseeScreen(c, actions, nav)
        Route.PAUSE -> PauseScreen(c, actions, nav)
        Route.REPRISE -> RepriseScreen(c, actions, nav)
        Route.MANUEL -> ManuelScreen(c, actions, nav)
        Route.PROGRAMME -> ProgrammeScreen(c, actions, nav)
        Route.ATTENTE -> AttenteScreen(c, actions, nav)
        Route.FIN -> FinScreen(c, actions, nav)
        Route.ETAPES -> EtapesScreen(c, actions, nav)
        Route.INCIDENT -> IncidentScreen(c, actions, nav)
        Route.RECETTES -> RecettesScreen(c, recipes, pictures, nav)
        Route.FICHE -> FicheScreen(c, recipes, pictures, nav)
        Route.ENVOYEE -> EnvoyeeScreen(c, actions, pictures, nav)
        Route.JOURNAL -> JournalScreen(c, journalActions, nav)
        Route.ROBOT -> RobotScreen(c, robotActions, nav)
        Route.APPAIRAGE -> AppairageScreen(c, pairing, nav)
        Route.DIAGNOSTIC -> DiagnosticScreen(c, nav)
        Route.EDITEUR -> EditeurScreen(c, edit, nav)
        Route.ETAPE -> EtapeScreen(c, edit, nav)
        Route.REGLAGE -> MonReglageScreen(c, ownStep, nav)
        Route.COURSES -> CoursesScreen(c, coursesActions, nav)
    }

    /** Les couleurs ont changé : tous les écrans construits jusqu'ici sont à refaire. */
    fun markStale() {
        stale.addAll(built.keys)
    }

    /** Des écrans attendent encore d'être refaits (voir [rebuildStale]). */
    val hasStale: Boolean get() = stale.isNotEmpty()

    /** L'écran de [route] est construit avec des couleurs d'avant. */
    fun isStale(route: Route): Boolean = route in stale

    /**
     * Refait les écrans périmés que [allowed] laisse refaire (les autres attendent un prochain appel) ;
     * renvoie les routes refaites. Le nouvel écran reprend l'état et le défilement de l'ancien.
     */
    fun rebuildStale(allowed: (Route, Screen<*>) -> Boolean): Set<Route> {
        val done = java.util.EnumSet.noneOf(Route::class.java)
        for (route in stale.toList()) {
            val old = built[route]
            if (old == null) {
                stale.remove(route)
                continue
            }
            if (!allowed(route, old)) continue
            val fresh = create(route)
            built[route] = fresh
            fresh.takeOver(old)
            stale.remove(route)
            done += route
        }
        return done
    }
}
