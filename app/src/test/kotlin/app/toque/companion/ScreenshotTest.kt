package app.toque.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import app.toque.companion.ui.LiveActions
import app.toque.companion.flows.AppearanceFlow
import app.toque.companion.flows.LookOffer
import app.toque.companion.flows.LookState
import app.toque.companion.flows.RobotFollow
import app.toque.companion.ui.Accent
import app.toque.companion.ui.ChoiceSheet
import app.toque.companion.ui.ColourWheelView
import app.toque.companion.ui.Looks
import app.toque.companion.ui.Palette
import app.toque.companion.ui.Ring
import app.toque.companion.ui.Route
import app.toque.companion.ui.Samples
import app.toque.companion.ui.Screens
import app.toque.companion.ui.Shell
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.live.LiveInput
import app.toque.companion.live.LivePresenter
import app.toque.companion.live.renderLive
import app.toque.companion.ui.screens.CameraAccess
import app.toque.companion.ui.screens.EditBanner
import app.toque.companion.ui.screens.EditPhase
import app.toque.companion.ui.screens.EditeurState
import app.toque.companion.ui.screens.EtapeState
import app.toque.companion.ui.screens.JournalState
import app.toque.companion.ui.screens.PairingError
import app.toque.companion.ui.screens.PauseCause
import app.toque.companion.ui.screens.RecettesState
import app.toque.companion.ui.screens.SendStatus
import app.toque.core.i18n.I18n
import app.toque.core.look.ColourWheel
import app.toque.core.i18n.tr
import app.toque.core.recipes.RecipeChip
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Captures d'écran sur la JVM (Robolectric, graphismes natifs) : chaque écran avec le contenu de
 * la maquette, en 390 × 844 dp (taille de la maquette), en 360 × 640 dp et en police à 130 %.
 * Les PNG vont dans companion/build/screenshots/. Le test échoue si un texte est coupé (sans
 * points de suspension) ou sort de l'écran.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    private val out = File(System.getProperty("toque.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private class Shot(val name: String, val route: Route, val custom: ((Context) -> View)? = null,
                       val prepare: (Screens, View) -> Unit = { _, _ -> })

    /** Écran « En direct » rendu depuis un état du fil, par le présentateur (comme dans l'appli). */
    private fun live(name: String, input: () -> LiveInput, after: (Shell) -> Unit = {}) = Shot(name, Route.REPOS) { s, root ->
        renderLive(s, root as Shell, LivePresenter.present(input()))
        after(root)
    }

    /** Écrans capturés aussi à mi-défilement (« -defile ») : en-tête ou nom fixé en haut. */
    private val MID_SCROLL = setOf("Editeur", "Fiche", "FicheSixPersonnes", "FicheFavoriBouton", "Journal", "EtapesArreterRecette", "Robot", "Manuel", "Fil-Manuel", "Fil-ManuelPreparation",
        "Envoyee", "Etapes", "Fil-FinAlerte")

    /** Écrans capturés, dans l'ordre de la maquette ; [Shot.prepare] règle une variante. */
    private val shots = listOf(
        Shot("Main", Route.MAIN),
        Shot("Etapes", Route.ETAPES),
        Shot("EtapesIngredients", Route.ETAPES) { _, root -> click(root, "Ingrédients", "Ingredients") },
        Shot("Pesee", Route.PESEE),
        Shot("Pause", Route.PAUSE),
        Shot("PauseBol", Route.PAUSE) { s, _ -> s.pause.render(Samples.pause().copy(cause = PauseCause.BOWL)) },
        Shot("Reprise", Route.REPRISE),
        Shot("Manuel", Route.MANUEL),
        Shot("Programme", Route.PROGRAMME),
        // Smart : un programme de l'appli d'origine en cours, et tous les programmes du Smart.
        Shot("ProgrammeSmart", Route.PROGRAMME) { s, _ ->
            // Sans chauffe, la base du Smart ne mesure pas : « — » (machine.tempKnown faux).
            s.programme.render(Samples.programme().copy(name = "Smoothie", bowlC = null, remainingMs = 40_000, totalMs = 60_000,
                tags = listOf("vitesse 10"), hint = "",
                programs = app.toque.companion.live.LivePresenter.robotPrograms(app.toque.companion.ui.screens.ProgramKind.SMOOTHIE, smart = true)))
        },
        Shot("Repos", Route.REPOS),
        // Smart au repos : température non mesurée, « — » et pourquoi.
        Shot("ReposSmart", Route.REPOS) { s, _ -> s.repos.render(Samples.repos().copy(bowlC = null)) },
        Shot("Attente", Route.ATTENTE),
        Shot("Fin", Route.FIN),
        // Titres longs : points de suspension, rien ne déborde.
        Shot("MainTitreLong", Route.MAIN) { s, _ -> s.main.render(Samples.main().copy(recipe = LONG)) },
        Shot("EtapesTitreLong", Route.ETAPES) { s, _ -> s.etapes.render(Samples.etapes().copy(recipe = LONG)) },
        // « Arrêter la recette » (robot qui le sait faire) et sa confirmation ; le Stop rouge ne change pas.
        Shot("MainArreterRecette", Route.MAIN) { s, _ -> s.main.render(Samples.main().copy(stopRecipeOffered = true, canStopRecipe = true)) },
        Shot("MainArreterRecetteConfirmation", Route.MAIN) { s, root ->
            s.main.render(Samples.main().copy(stopRecipeOffered = true, canStopRecipe = true))
            click(root, "Arrêter la recette", "Stop the recipe")
        },
        Shot("EtapesArreterRecette", Route.ETAPES) { s, _ -> s.etapes.render(Samples.etapes().copy(stopRecipeOffered = true, canStopRecipe = true)) },
        Shot("EtapesArreterRecetteConfirmation", Route.ETAPES) { s, root ->
            s.etapes.render(Samples.etapes().copy(now = null, stopRecipeOffered = true, canStopRecipe = true))
            click(root, "Arrêter la recette", "Stop the recipe")
        },
        Shot("AttenteTitreLong", Route.ATTENTE) { s, _ -> s.attente.render(Samples.attente().copy(recipe = LONG)) },

        // Recettes, fiche, recette envoyée.
        Shot("Recettes", Route.RECETTES),
        Shot("RecettesChargement", Route.RECETTES) { s, _ -> s.recettes.render(RecettesState(loading = true)) },
        Shot("RecettesPageSuivante", Route.RECETTES) { s, _ -> s.recettes.render(Samples.recettes().copy(loading = true)) },
        Shot("RecettesVide", Route.RECETTES) { s, _ -> s.recettes.render(RecettesState(query = "salsifis")) },
        // Nouvelle recherche en route : les cartes d'avant restent, estompées, avec un petit signe de chargement.
        Shot("RecettesRecherche", Route.RECETTES) { s, _ -> s.recettes.render(Samples.recettes().copy(query = "potimarron", loading = true, stale = true)) },
        Shot("RecettesErreur", Route.RECETTES) { s, _ -> s.recettes.render(RecettesState(error = "Le robot ne répond pas.")) },
        // Pastilles du robot : une catégorie choisie au bout de la ligne, ramenée à l'écran ; « Mes recettes » vide.
        Shot("RecettesCategorie", Route.RECETTES) { s, _ ->
            s.recettes.render(Samples.recettes().let { r ->
                r.copy(filter = RecipeChip.category("Soupes"), items = r.items.filter { it.category == "Soupes" }, hasMore = false)
            })
        },
        Shot("RecettesMesRecettesVide", Route.RECETTES) { s, _ ->
            s.recettes.render(RecettesState(filter = RecipeChip.USER, filters = Samples.recipeChips))
        },
        Shot("RecettesTitreLong", Route.RECETTES) { s, _ ->
            s.recettes.render(Samples.recettes().let { r -> r.copy(items = r.items.map { it.copy(title = LONG, category = "Soupes et veloutés de saison") }) })
        },
        Shot("Fiche", Route.FICHE),
        Shot("FicheEtapes", Route.FICHE) { _, root -> click(root, "Étapes", "Steps") },
        Shot("FicheChargement", Route.FICHE) { s, _ -> s.fiche.render(Samples.fiche().copy(loading = true, ingredients = emptyList(), steps = emptyList())) },
        Shot("FicheBloquee", Route.FICHE) { s, _ ->
            s.fiche.render(Samples.fiche().copy(send = SendStatus.BLOCKED, sendNote = "Une autre recette attend déjà sur le robot. Retirez-la d'abord."))
        },
        // Nombre de personnes : changé (notice), bol trop rempli, confirmation la première fois.
        Shot("FicheSixPersonnes", Route.FICHE) { s, _ -> s.fiche.render(Samples.ficheSix()) },
        Shot("FicheBolTropRempli", Route.FICHE) { s, _ -> s.fiche.render(Samples.ficheHuit()) },
        Shot("FicheQuantitesEnAttente", Route.FICHE) { s, _ -> s.fiche.render(Samples.ficheEnAttente()) },
        Shot("FicheConfirmationPersonnes", Route.FICHE) { _, root -> clickDescribed(root, "Plus de personnes", "More people") },
        Shot("FicheTitreLong", Route.FICHE) { s, _ -> s.fiche.render(Samples.fiche().copy(title = LONG, personal = false, category = "Soupes")) },
        // Robot qui enregistre les favoris choisis ici : l'étoile est un bouton (pleine, creuse).
        Shot("FicheFavoriBouton", Route.FICHE) { s, _ -> s.fiche.render(Samples.fiche().copy(canFavorite = true)) },
        Shot("FicheFavoriCreux", Route.FICHE) { s, _ -> s.fiche.render(Samples.fiche().copy(canFavorite = true, favorite = false)) },
        Shot("FicheFavoriTitreLong", Route.FICHE) { s, _ -> s.fiche.render(Samples.fiche().copy(canFavorite = true, title = LONG)) },
        Shot("RecettesFavoris", Route.RECETTES) { s, _ -> s.recettes.render(Samples.recettes().copy(canFavorite = true)) },
        Shot("RecettesFavorisVide", Route.RECETTES) { s, _ ->
            s.recettes.render(RecettesState(filter = RecipeChip.FAVORITES, filters = Samples.recipeChips, canFavorite = true))
        },
        Shot("Envoyee", Route.ENVOYEE),
        Shot("EnvoyeeEnRoute", Route.ENVOYEE) { s, _ -> s.envoyee.render(Samples.envoyee().copy(receivedAt = null)) },
        Shot("EnvoyeeTitreLong", Route.ENVOYEE) { s, _ -> s.envoyee.render(Samples.envoyee().copy(title = LONG)) },

        // Journal et Robot.
        // Liste de courses : à acheter, dans le panier ; vide.
        Shot("Courses", Route.COURSES),
        Shot("CoursesVide", Route.COURSES) { s, _ -> s.courses.render(app.toque.companion.ui.screens.CoursesState()) },
        // Mise à jour trouvée sur GitHub, téléchargement en cours, erreur.
        Shot("RobotMiseAJour", Route.ROBOT) { s, _ ->
            s.robot.render(Samples.robot().copy(update = app.toque.companion.update.UpdateView("0.5.0", app.toque.companion.update.UpdatePhase.AVAILABLE, "0.5.1")))
        },
        Shot("RobotMiseAJourTelechargement", Route.ROBOT) { s, _ ->
            s.robot.render(Samples.robot().copy(language = "en",
                update = app.toque.companion.update.UpdateView("0.5.0", app.toque.companion.update.UpdatePhase.DOWNLOADING, "0.5.1", percent = 42)))
        },
        Shot("RobotMiseAJourErreur", Route.ROBOT) { s, _ ->
            s.robot.render(Samples.robot().copy(update = app.toque.companion.update.UpdateView("0.5.0", app.toque.companion.update.UpdatePhase.ERROR,
                error = "GitHub limite les vérifications : réessayez dans une heure.")))
        },
        Shot("Journal", Route.JOURNAL),
        Shot("JournalVide", Route.JOURNAL) { s, _ -> s.journal.render(JournalState()) },
        Shot("Robot", Route.ROBOT),
        Shot("RobotOublier", Route.ROBOT) { _, root -> click(root, "Oublier", "Forget") },
        Shot("RobotNotificationsRefusees", Route.ROBOT) { s, _ -> s.robot.render(Samples.robot().copy(notificationsAllowed = false)) },
        Shot("RobotBatterieLimitee", Route.ROBOT) { s, _ -> s.robot.render(Samples.robot().copy(batteryRestricted = true)) },
        Shot("RobotSuiviCoupe", Route.ROBOT) { s, _ -> s.robot.render(Samples.robot().copy(background = false, batteryRestricted = true)) },
        Shot("RobotAlertesSilencieuses", Route.ROBOT) { s, _ -> s.robot.render(Samples.robot().copy(alertsMuted = true)) },
        Shot("Diagnostic", Route.DIAGNOSTIC),

        // Appairage (plein écran, sans onglets).
        Shot("Appairage", Route.APPAIRAGE),
        Shot("AppairagePermission", Route.APPAIRAGE) { s, _ -> s.appairage.render(Samples.appairage().copy(camera = CameraAccess.ASK)) },
        Shot("AppairageRefusee", Route.APPAIRAGE) { s, _ -> s.appairage.render(Samples.appairage().copy(camera = CameraAccess.DENIED)) },
        Shot("AppairageEnCours", Route.APPAIRAGE) { s, _ -> s.appairage.render(Samples.appairage().copy(busy = true)) },
        Shot("AppairageErreur", Route.APPAIRAGE) { s, _ -> s.appairage.render(Samples.appairage().copy(error = PairingError.NOT_A_TOQUE_CODE)) },
        Shot("AppairageCode", Route.APPAIRAGE) { s, root -> s.appairage.showCodeEntry(true); typeCode(root, "4829") },
        Shot("AppairageCodeAdresse", Route.APPAIRAGE) { s, root ->
            s.appairage.render(Samples.appairage().copy(askAddress = true, error = PairingError.ROBOT_NOT_FOUND))
            s.appairage.showCodeEntry(true)
            typeCode(root, "482913")
        },
        Shot("AppairageCodeEnCours", Route.APPAIRAGE) { s, root ->
            s.appairage.showCodeEntry(true)
            typeCode(root, "482913")
            s.appairage.render(Samples.appairage().copy(busy = true))
        },
        Shot("AppairageCodeExpire", Route.APPAIRAGE) { s, root ->
            s.appairage.showCodeEntry(true)
            typeCode(root, "482913")
            s.appairage.render(Samples.appairage().copy(error = PairingError.CODE_EXPIRED))
        },
    ) + LiveShots.all.map { (name, input) -> live(name, input) } + TabShots.all.map { (name, route, render) ->
        Shot(name, route) { s, _ -> render(s) }
    } + listOf(
        // Refus du robot : son message, au-dessus de la barre d'actions.
        live("Fil-Refus", LiveShots.all.first { it.first == "Fil-Main" }.second) { it.notice("Le couvercle a été ouvert pendant la pause : reprenez sur le robot") },
        // Correction automatique en préparation : le message du robot, comme son toast.
        live("Fil-ManuelPreparationCorrection", LiveShots.all.first { it.first == "Fil-ManuelPreparationChauffe" }.second) {
            it.notice("En chauffe, vitesse 3 maximum")
        },
        Shot("RobotLancementCoupe", Route.ROBOT) { s, _ -> s.robot.render(Samples.robot().copy(manualStartOff = "Coupé sur le robot")) },

        // Apparence du téléphone (section de l'onglet Robot), les questions, un refus du robot occupé.
        Shot("RobotApparence", Route.ROBOT) { s, _ -> s.robot.render(Samples.robot().copy(look = robotLooksElse())) },
        Shot("RobotApparenceEnvoi", Route.ROBOT) { s, root ->
            s.robot.render(Samples.robot().copy(look = robotLooksElse().copy(sending = true)))
            (root as Shell).notice(tr("Le robot n'est pas sur son écran d'accueil : revenez-y sur le robot, puis réessayez"))
        },
        // La roue (feuille de l'onglet Robot) sur une couleur de la roue, et la section avec cette couleur cochée.
        Shot("RobotRoue", Route.ROBOT) { s, _ -> s.robot.openWheel().show(Ui.ACCENT) },
        Shot("RobotRoueVert", Route.ROBOT) { s, _ -> s.robot.openWheel().show(ColourWheel.colour(150.0, 0.8)) },
        Shot("RobotApparenceRoue", Route.ROBOT) { s, _ ->
            s.robot.render(Samples.robot().copy(look = LookState(accent = ColourWheel.colour(150.0, 0.8), light = Ui.light)))
        },
        Shot("QuestionRobotRoue", Route.ROBOT) { _, root ->
            (root as Shell).showSheet(ChoiceSheet.follow(root.context, RobotFollow(ColourWheel.colour(150.0, 0.8), null)) { })
        },
        Shot("QuestionApparenceUne", Route.RECETTES) { _, root ->
            (root as Shell).showSheet(ChoiceSheet.offer(root.context, LookOffer(0xFFE8D44D.toInt(), null)) { _, _, _ -> })
        },
        Shot("QuestionApparenceDeux", Route.RECETTES) { _, root ->
            (root as Shell).showSheet(ChoiceSheet.offer(root.context, LookOffer(0xFFE8D44D.toInt(), !Ui.light)) { _, _, _ -> })
        },
        Shot("QuestionRobot", Route.ROBOT) { _, root ->
            (root as Shell).showSheet(ChoiceSheet.follow(root.context, RobotFollow(Ui.ACCENT, null)) { })
        },
        Shot("QuestionRobotTheme", Route.ROBOT) { _, root ->
            (root as Shell).showSheet(ChoiceSheet.follow(root.context, RobotFollow(null, Ui.light)) { })
        },

        // Écrire ses recettes (robot qui le sait faire) : entrées, éditeur, étape, refus, brouillon.
        Shot("RecettesMesRecettesEcrire", Route.RECETTES) { s, _ ->
            s.recettes.render(Samples.recettes().let { r -> r.copy(filter = RecipeChip.USER, filters = Samples.recipeChips, canWrite = true,
                items = r.items.filter { it.personal }, hasMore = false) })
        },
        Shot("RecettesMesRecettesEcrireVide", Route.RECETTES) { s, _ ->
            s.recettes.render(RecettesState(filter = RecipeChip.USER, filters = Samples.recipeChips, canWrite = true))
        },
        Shot("FicheModifier", Route.FICHE) { s, _ -> s.fiche.render(Samples.fiche().copy(canWrite = true, canFavorite = true)) },
        Shot("FicheMaVersion", Route.FICHE) { s, _ -> s.fiche.render(Samples.fiche().copy(canWrite = true, personal = false, category = "Soupes")) },
        Shot("FicheMaVersionConfirmation", Route.FICHE) { s, root ->
            s.fiche.render(Samples.fiche().copy(canWrite = true, personal = false, category = "Soupes"))
            click(root, "Ma version", "My version")
        },
        Shot("Editeur", Route.EDITEUR),
        Shot("EditeurNouvelle", Route.EDITEUR) { s, _ -> s.editeur.render(EditeurState(heading = tr("Nouvelle recette"), phase = EditPhase.EDITING)) },
        Shot("EditeurRefus", Route.EDITEUR) { s, _ -> s.editeur.render(Samples.editeurRefus()) },
        Shot("EditeurChamp", Route.EDITEUR) { s, _ ->
            s.editeur.render(Samples.editeur().copy(title = "", banner = EditBanner(EditBanner.Kind.REFUSED, tr("Le robot n'a pas enregistré la recette."),
                tr("Donnez un titre à la recette")), problemField = "title", problemText = tr("Donnez un titre à la recette")))
        },
        Shot("EditeurVersion", Route.EDITEUR) { s, _ ->
            s.editeur.render(Samples.editeur().copy(banner = EditBanner(EditBanner.Kind.STALE, tr("La recette a changé sur le robot"),
                tr("Reprenez la version du robot, ou gardez votre brouillon et enregistrez-le par-dessus."))))
        },
        Shot("EditeurHorsLigne", Route.EDITEUR) { s, _ ->
            s.editeur.render(Samples.editeur().copy(note = tr("Robot injoignable : le brouillon reste sur le téléphone"),
                banner = EditBanner(EditBanner.Kind.OFFLINE, tr("Le robot ne répond pas"), tr("Le brouillon reste sur le téléphone : enregistrez quand le robot sera joignable."))))
        },
        Shot("EditeurBrouillon", Route.EDITEUR) { s, _ ->
            s.editeur.render(EditeurState(heading = tr("Modifier la recette"), phase = EditPhase.OFFER, message = tr("Brouillon du %s, gardé sur le téléphone.", "23/11 12:02")))
        },
        Shot("EditeurChargement", Route.EDITEUR) { s, _ ->
            s.editeur.render(EditeurState(heading = tr("Ma version"), phase = EditPhase.LOADING, message = tr("Création de votre version sur le robot…")))
        },
        Shot("EditeurQuitter", Route.EDITEUR) { _, root -> clickDescribed(root, "Retour", "Back") },
        Shot("EditeurTitreLong", Route.EDITEUR) { s, _ -> s.editeur.render(Samples.editeur().copy(title = LONG,
            steps = Samples.editeur().steps.map { it.copy(text = LONG + " " + LONG) })) },
        Shot("Etape", Route.ETAPE),
        Shot("EtapeNouvelle", Route.ETAPE) { s, _ -> s.etape.render(EtapeState(4, isNew = true)) },
        Shot("EtapeRefus", Route.ETAPE) { s, _ -> s.etape.render(EtapeState(3, "Ajouter la crème et servir.", 9000,
            refused = "Étape 3 : Entrez un poids entre 1 et 5000 g")) },
        Shot("EtapeSupprimer", Route.ETAPE) { _, root -> click(root, "Supprimer l'étape", "Delete step") },
        Shot("EtapeAncienRobot", Route.ETAPE) { s, _ -> s.etape.render(Samples.etapeAncienRobot()) },
        Shot("EtapeReglageManuel", Route.ETAPE) { s, _ -> s.etape.render(Samples.etapeReglageManuel()) },
        Shot("EtapeReglageProgramme", Route.ETAPE) { s, _ -> s.etape.render(Samples.etapeReglageProgramme()) },
        Shot("EtapeReglageRefuse", Route.ETAPE) { s, _ -> s.etape.render(Samples.etapeReglageRefuse()) },
    )

    /** Section « Apparence » : le robot a une autre couleur et l'autre thème, et sait les recevoir. */
    private fun robotLooksElse() = LookState(accent = Ui.ACCENT, light = Ui.light, robotAccent = 0xFFE8D44D.toInt(), robotLight = !Ui.light,
        canTake = true, canSend = true)

    /** Saisit [code] dans le champ invisible des six cases. */
    private fun typeCode(root: View, code: String) {
        fun find(v: View): EditText? {
            if (v is EditText && v.inputType == android.text.InputType.TYPE_CLASS_NUMBER) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(root)!!.setText(code)
    }

    private val LONG = "Velouté de potimarron rôti au four, châtaignes, crème fraîche épaisse et éclats de noisettes torréfiées"

    private fun click(root: View, vararg labels: String) {
        fun find(v: View): View? {
            if (v is TextView && v.text.toString() in labels) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        // Le texte lui-même (onglet) ou le bouton qui le contient.
        var v: View = find(root)!!
        while (!v.isClickable) v = v.parent as View
        v.performClick()
    }

    /** Touche le bouton d'icône décrit par l'un des [labels] (contentDescription). */
    private fun clickDescribed(root: View, vararg labels: String) {
        fun find(v: View): View? {
            if (v.contentDescription?.toString() in labels) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(root)!!.performClick()
    }

    @After fun reset() {
        I18n.install(emptyList())
        I18n.select("fr")
    }

    @Test fun mockupSize() = capture("w390dp-h844dp-port-xhdpi", 1f, "")

    @Test fun smallPhone() = capture("w360dp-h640dp-port-xhdpi", 1f, "-360x640")

    @Test fun largeFont() = capture("w390dp-h844dp-port-xhdpi", 1.3f, "-font130")

    @Test fun english() = capture("w390dp-h844dp-port-xhdpi", 1f, "-en", language = "en")

    /** Ukrainien : textes plus longs, autre alphabet. */
    @Test fun ukrainian() = capture("w390dp-h844dp-port-xhdpi", 1f, "-uk", language = "uk", only = UK_SHOTS)

    /** Tablette : menu des onglets sur le côté, écran centré ; catalogue à gauche de la fiche. */
    @Test fun tablet() = capture("w1280dp-h800dp-land-mdpi", 1f, "-tablette", only = WIDE_SHOTS, wide = true)

    /** Téléphone à l'horizontale : comme une tablette, en moins haut. */
    @Test fun phoneLandscape() = capture("w844dp-h390dp-land-xhdpi", 1f, "-paysage", only = WIDE_SHOTS, wide = true)

    /** Tous les écrans en thème clair (orange) : rien du thème sombre n'y reste. */
    @Test fun lightTheme() = capture("w390dp-h844dp-port-xhdpi", 1f, "-clair", theme = "light")

    /** Écrans clés avec trois autres couleurs (ciel, citron, framboise), sur les deux thèmes : rien ne reste orange. */
    @Test fun otherColours() {
        for (theme in listOf("dark", "light")) for (accent in listOf("4FC3F7", "E8D44D", "F2639A"))
            capture("w390dp-h844dp-port-xhdpi", 1f, (if (theme == "light") "-clair" else "") + "-accent-$accent",
                only = ACCENT_SHOTS, accent = "#$accent", theme = theme)
    }

    /**
     * Une couleur de la roue (un vert, hors des dix) appliquée au téléphone, sur les deux thèmes : l'écran
     * « En direct », l'onglet Robot, la rubrique Apparence avec cette couleur cochée, et la question
     * « Appliquer aussi au robot ? » telle qu'elle apparaît après « Appliquer ».
     */
    @Test fun aWheelColourApplied() {
        val hex = AppearanceFlow.hexOf(ColourWheel.colour(150.0, 0.8))
        for (theme in listOf("dark", "light"))
            capture("w390dp-h844dp-port-xhdpi", 1f, (if (theme == "light") "-clair" else "") + "-roue",
                only = setOf("Main", "Robot", "RobotRoue", "RobotApparenceRoue", "QuestionRobotRoue"), accent = hex, theme = theme)
    }

    private val WIDE_SHOTS = setOf("Repos", "Main", "Recettes", "Fiche", "FicheEtapes", "Envoyee", "Courses", "Robot", "Journal", "Appairage", "Editeur")
    private val UK_SHOTS = setOf("ProgrammeSmart", "Repos", "ReposSmart", "Main", "Etapes", "Pesee", "Pause", "Recettes", "Fiche", "Courses", "CoursesVide", "Robot", "RobotMiseAJour",
        "Journal", "Appairage", "AppairageCode", "Editeur", "Manuel", "Programme")

    /** Écran « En direct » avec une cuisson en cours, catalogue, fiche, onglet Robot, éditeur, programme en cours, apparence. */
    private val ACCENT_SHOTS = setOf("Main", "Etapes", "Programme", "Recettes", "Fiche", "Robot", "Editeur", "Attente", "Fil-Main",
        "Etape", "EtapeReglageManuel", "EtapeReglageProgramme", "EtapeReglageRefuse",
        "RobotApparence", "QuestionApparenceDeux", "QuestionRobot", "RobotRoue")

    private fun capture(qualifiers: String, fontScale: Float, suffix: String, language: String = "fr", only: Set<String>? = null,
                        accent: String = "", theme: String = "", wide: Boolean = false) {
        // L'anneau de la roue dessiné tout de suite, pas sur un autre fil après la capture.
        ColourWheelView.Ring.inline = true
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        val app = RuntimeEnvironment.getApplication()
        val c: Context = ContextThemeWrapper(app, R.style.Companion)
        Ui.init(c)
        CompanionLanguages.install(c, language)
        // Après l'appli (qui pose l'apparence gardée, ici aucune) : celle de cette série d'images.
        Ui.setLook(accent, theme)
        try {
            captureAll(c, suffix, only, wide)
        } finally {
            Ui.setLook("")
        }
    }

    private fun captureAll(c: Context, suffix: String, only: Set<String>?, wide: Boolean = false) {
        val dm = c.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val problems = ArrayList<String>()
        var rings = 0
        var badges = 0
        for (shot in shots) {
            if (only != null && shot.name !in only) continue
            val name = shot.name
            val route = shot.route
            val shell = Shell(c)
            val screens = Screens(c, object : LiveActions {}, shell)
            shell.screens = screens
            Samples.renderAll(screens)
            if (wide) shell.setWide(true)
            when {
                route == Route.APPAIRAGE -> shell.showPairing(true)
                route.tab == Tab.LIVE && route != Route.ETAPES -> shell.showLive(route)
                else -> shell.open(route)
            }
            shell.refresh()
            shot.prepare(screens, shell)
            val root: View = shot.custom?.invoke(c) ?: shell
            layout(root, w, h)
            save(root, w, h, "$name$suffix")
            check(root, w, "$name$suffix", problems)
            if (Ui.ACCENT != Accent.DEFAULT) Looks.find(root, Looks.ORANGE).forEach { problems += "ERREUR $name$suffix : orange restant, $it" }
            // Rien d'un thème sur l'autre (photo et caméra à part).
            val other = Looks.themeColours(if (Ui.light) Palette.DARK else Palette.LIGHT) - Looks.themeColours(if (Ui.light) Palette.LIGHT else Palette.DARK)
            Looks.find(root, other).forEach { problems += "ERREUR $name$suffix : couleur de l'autre thème, $it" }
            rings += checkReadyRing(root, "$name$suffix", problems)
            badges += checkFavoriteBadge(root, "$name$suffix", problems)
            // Contenu plus haut que l'écran : seconde capture, défilée jusqu'en bas.
            // Feuille posée sur l'écran : c'est son milieu qui défile.
            val scroll = if (name in SHEET_SCROLL) findScrolls(root).lastOrNull() else findScroll(root)
            val child = scroll?.getChildAt(0)
            if (scroll != null && child != null && child.height > scroll.height) {
                // À mi-course : le haut fixe reste, le contenu glisse dessous (fondu).
                if (name in MID_SCROLL) {
                    scroll.scrollTo(0, minOf(Ui.dp(160), child.height - scroll.height))
                    save(root, w, h, "$name$suffix-defile")
                }
                scroll.scrollTo(0, child.height - scroll.height)
                save(root, w, h, "$name$suffix-bas")
            }
        }
        problems.forEach { println(it) }
        assertTrue("Textes coupés ou hors de l'écran :\n" + problems.joinToString("\n"), problems.none { it.startsWith("ERREUR") })
        if (only != null) return
        assertTrue("l'écran de repos et son cercle n'ont pas été contrôlés", rings > 0)
        assertTrue("la marque « Favori » de la fiche n'a pas été contrôlée", badges > 0)
    }

    /**
     * « Base prête » dans le cercle de l'écran de repos : sur une seule ligne, entier, dans le cercle,
     * et centré avec sa pastille (un texte plus long que prévu passait à la ligne et décalait tout).
     * Renvoie le nombre de cercles contrôlés.
     */
    private fun checkReadyRing(root: View, shot: String, problems: MutableList<String>): Int {
        val label = tr("Base prête")
        var seen = 0
        fun visit(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView && v.text.toString() == label) {
                val row = v.parent as? ViewGroup
                val box = (row?.parent as? View)?.parent as? ViewGroup
                if (row != null && box != null && (0 until box.childCount).any { box.getChildAt(it) is Ring }) {
                    seen++
                    val l = v.layout
                    if (l == null || l.lineCount != 1 || l.getEllipsisCount(0) > 0)
                        problems += "ERREUR $shot : « $label » sur ${l?.lineCount} ligne(s) ou coupé dans le cercle"
                    // Position dans le cercle par les marges successives (les vues ne sont pas dans une fenêtre).
                    var x = 0
                    var up: View = row
                    while (up !== box) {
                        x += up.left
                        up = up.parent as View
                    }
                    val off = (x + row.width / 2f) - box.width / 2f
                    if (kotlin.math.abs(off) > 2f) problems += "ERREUR $shot : « $label » décentré de $off px dans le cercle"
                    // Le trait du cercle fait 12 dp : le contenu reste nettement à l'intérieur.
                    if (row.width > box.width - 2 * Ui.dp(20)) problems += "ERREUR $shot : « $label » déborde du cercle (${row.width} px dans ${box.width})"
                    if (v.textSize < Ui.dp(18)) problems += "ERREUR $shot : « $label » trop petit dans le cercle (${v.textSize} px)"
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        return seen
    }

    /**
     * Marque « Favori » de la fiche : une étiquette sur une ligne, entière, qui n'est pas un bouton, et
     * qui laisse au titre de la recette au moins la moitié de la barre. Renvoie le nombre de marques vues.
     */
    private fun checkFavoriteBadge(root: View, shot: String, problems: MutableList<String>): Int {
        val description = tr("Favori sur le robot")
        var seen = 0
        fun visit(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is ViewGroup && v.contentDescription?.toString() == description) {
                seen++
                if (v.isClickable || v.isFocusable) problems += "ERREUR $shot : la marque « Favori » se comporte comme un bouton"
                val label = (0 until v.childCount).map { v.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull()
                val l = label?.layout
                if (l == null || l.lineCount != 1 || l.getEllipsisCount(0) > 0) problems += "ERREUR $shot : « Favori » coupé ou sur plusieurs lignes"
                if (v.height > Ui.dp(32)) problems += "ERREUR $shot : marque « Favori » trop haute (${v.height} px)"
                val bar = v.parent as? ViewGroup
                if (bar != null && v.width * 2 > bar.width) problems += "ERREUR $shot : marque « Favori » trop large (${v.width} px sur ${bar.width})"
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        return seen
    }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun save(v: View, w: Int, h: Int, name: String) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Écrans dont une feuille ouverte défile (sa capture « -bas » montre le bas de la feuille). */
    private val SHEET_SCROLL = setOf("EtapeReglageManuel", "EtapeReglageProgramme")

    private fun findScrolls(v: View, out: MutableList<ScrollView> = ArrayList()): List<ScrollView> {
        if (v.visibility != View.VISIBLE) return out
        if (v is ScrollView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScrolls(v.getChildAt(i), out)
        return out
    }

    private fun findScroll(v: View): ScrollView? {
        if (v.visibility != View.VISIBLE) return null
        if (v is ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun describe(v: View): String {
        if (v is TextView) return "« ${v.text.toString().take(40)} »"
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            val d = describe(v.getChildAt(i))
            if (d.startsWith("«")) return "${v.javaClass.simpleName} avec $d"
        }
        return v.javaClass.simpleName
    }

    /** Textes coupés sans points de suspension, ou qui sortent de l'écran. */
    /** [inRow] : dans une ligne qui défile à l'horizontale, où sortir de l'écran est voulu. */
    private fun check(v: View, screenW: Int, shot: String, problems: MutableList<String>, inRow: Boolean = false) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView && v.text.isNotEmpty()) {
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            val l = v.layout
            val label = "$shot : « ${v.text.toString().take(40)} »"
            if (!inRow && (loc[0] < 0 || loc[0] + v.width > screenW + 1)) problems += "ERREUR hors de l'écran $label (x=${loc[0]}, w=${v.width})"
            if (l != null) {
                val ellipsized = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
                val needed = l.height + v.compoundPaddingTop + v.compoundPaddingBottom
                if (needed > v.height + 2 && !ellipsized) problems += "ERREUR coupé en hauteur $label (${v.height} px pour $needed)"
                val avail = v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 1
                val wide = (0 until l.lineCount).any {
                    val line = v.text.subSequence(l.getLineStart(it), l.getLineEnd(it)).toString().trimEnd()
                    v.paint.measureText(line) > avail && l.getEllipsisCount(it) == 0 && v.text !is android.text.Spanned
                }
                if (wide && !ellipsized) problems += "ERREUR coupé en largeur $label"
                if (ellipsized) problems += "info : points de suspension $label"
            }
        }
        // Listes (grille des recettes) : les cartes du bas sont coupées par le défilement, c'est voulu.
        if (v is AbsListView) for (i in 0 until v.childCount) check(v.getChildAt(i), screenW, shot, problems)
        // Ligne de pastilles qui défile : chaque pastille doit être lisible entière, pas forcément à l'écran.
        if (v is HorizontalScrollView) {
            val row = v.getChildAt(0)
            if (row is ViewGroup) for (i in 0 until row.childCount) check(row.getChildAt(i), screenW, shot, problems, inRow = true)
            return
        }
        if (v is ViewGroup && v !is ScrollView && v !is AbsListView) for (i in 0 until v.childCount) {
            val ch = v.getChildAt(i)
            // Enfant qui dépasse de son parent : il serait coupé à l'affichage.
            if (ch.visibility == View.VISIBLE && ch.height > 0 && (ch.bottom > v.height + 1 || ch.right > v.width + 1))
                problems += "ERREUR dépasse de son parent $shot : ${describe(ch)} (${ch.right}×${ch.bottom} dans ${v.width}×${v.height})"
            check(ch, screenW, shot, problems)
        }
        if (v is ScrollView) {
            val child = v.getChildAt(0)
            if (child != null && child.height > v.height) problems += "info : $shot défile (${child.height - v.height} px de plus)"
            if (child != null) check(child, screenW, shot, problems)
        }
    }
}
