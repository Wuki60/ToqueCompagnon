package app.toque.companion.ui

import app.toque.companion.ui.screens.AlertKind
import app.toque.companion.ui.screens.AppairageState
import app.toque.companion.ui.screens.AttenteState
import app.toque.companion.ui.screens.CourseRow
import app.toque.companion.ui.screens.CoursesState
import app.toque.companion.ui.screens.EditBanner
import app.toque.companion.ui.screens.EditLine
import app.toque.companion.ui.screens.EditPhase
import app.toque.companion.ui.screens.EditStepRow
import app.toque.companion.ui.screens.EditeurState
import app.toque.companion.ui.screens.EnvoyeeState
import app.toque.companion.ui.screens.EtapeState
import app.toque.companion.ui.screens.EtapeItem
import app.toque.companion.ui.screens.EtapesState
import app.toque.companion.ui.screens.FicheState
import app.toque.companion.ui.screens.FicheStep
import app.toque.companion.ui.screens.FinState
import app.toque.companion.ui.screens.IngredientItem
import app.toque.companion.ui.screens.JournalDay
import app.toque.companion.ui.screens.JournalEntry
import app.toque.companion.ui.screens.JournalKind
import app.toque.companion.ui.screens.JournalOutcome
import app.toque.companion.ui.screens.JournalState
import app.toque.companion.ui.screens.LastActivity
import app.toque.companion.ui.screens.MainState
import app.toque.companion.ui.screens.ManuelState
import app.toque.companion.ui.screens.MonReglageState
import app.toque.companion.ui.screens.NowBar
import app.toque.companion.ui.screens.PauseCause
import app.toque.companion.ui.screens.PairMethod
import app.toque.companion.ui.screens.PauseState
import app.toque.companion.ui.screens.PeseeState
import app.toque.companion.ui.screens.ProgramItem
import app.toque.companion.ui.screens.ProgramKind
import app.toque.companion.ui.screens.ProgrammeState
import app.toque.companion.ui.screens.RecettesState
import app.toque.companion.ui.screens.RecipeCard
import app.toque.companion.ui.screens.ReposState
import app.toque.companion.ui.screens.RepriseState
import app.toque.companion.ui.screens.RobotState
import app.toque.companion.ui.screens.DiagnosticState
import app.toque.companion.ui.screens.StepProgress
import app.toque.companion.ui.screens.WeighItem
import app.toque.core.i18n.tr
import app.toque.core.recipes.RecipeChip

/**
 * Exemples : exactement le contenu de la maquette (local/compagnon-maquette). Servent aux captures
 * d'écran des tests et à l'appli tant qu'elle n'est pas reliée au robot. Les textes de recette sont
 * des données (pas de tr()) ; les réglages passent par [Fmt] pour être traduits.
 */
object Samples {
    const val RECIPE = "Velouté de potimarron"
    private fun ms(min: Int, s: Int) = (min * 60 + s) * 1000L

    fun main() = MainState(
        recipe = RECIPE,
        stepIndex = 4,
        stepCount = 6,
        action = "Cuire",
        remainingMs = ms(12, 40),
        totalMs = ms(20, 0),
        settings = Fmt.cooking(20 * 60, 100, 1),
        tempC = 96,
        targetC = 100,
        speed = 1,
        lidClosed = true,
        lidLocked = true,
        instruction = "Laissez cuire jusqu'à ce que le potimarron soit tendre.",
        nextByHand = "Ajouter 100 g de crème",
    )

    fun etapes() = EtapesState(
        recipe = RECIPE,
        servings = 4,
        steps = listOf(
            EtapeItem("Préparer les légumes", tr("À la main"), StepStatus.DONE),
            EtapeItem("Peser 20 g de beurre", tr("Pesée · %s", Fmt.grams(21)), StepStatus.DONE),
            EtapeItem("Faire revenir l'oignon", Fmt.cooking(3 * 60, 100, 1), StepStatus.DONE),
            EtapeItem("Ajouter le potimarron et 500 g d'eau", tr("Pesée · %s", Fmt.grams(503)), StepStatus.DONE),
            EtapeItem("Cuire", Fmt.cooking(20 * 60, 100, 1), StepStatus.CURRENT,
                progress = StepProgress(0.37f, ms(12, 40), 96)),
            EtapeItem("Ajouter 100 g de crème, mixer", Fmt.cooking(60, null, 8), StepStatus.TODO,
                warning = tr("Contenu chaud : à confirmer sur le robot")),
        ),
        // Pas dans la maquette (onglet Ingrédients) : ingrédients cités par les étapes.
        ingredients = listOf(
            IngredientItem("Potimarron en dés", Fmt.grams(600)),
            IngredientItem("Oignon", "1"),
            IngredientItem("Beurre", Fmt.grams(20)),
            IngredientItem("Eau", Fmt.grams(500)),
            IngredientItem("Crème", Fmt.grams(100)),
        ),
        now = NowBar("Cuire", ms(12, 40)),
    )

    fun pesee() = PeseeState(
        recipe = RECIPE,
        stepIndex = 3,
        stepCount = 6,
        instruction = "Ajoutez 500 g d'eau",
        weightG = 372,
        targetG = 500,
        items = listOf(
            WeighItem("Potimarron en dés", done = true, weightG = 604),
            WeighItem("Eau", done = false, weightG = 372, targetG = 500),
        ),
        nextAction = "Cuire",
        nextSettings = Fmt.cooking(20 * 60, 100, 1),
    )

    fun pause() = PauseState(
        recipe = RECIPE,
        stepIndex = 4,
        stepCount = 6,
        cause = PauseCause.LID,
        remainingMs = ms(12, 40),
        autoCancelMs = ms(9, 12),
    )

    fun reprise() = RepriseState(
        recipe = RECIPE,
        stepIndex = 4,
        stepCount = 6,
        remainingMs = ms(12, 40),
        autoCancelMs = ms(9, 12),
    )

    fun manuel() = ManuelState(
        startedAt = "11:48",
        bowlC = 86,
        remainingMs = ms(5, 12),
        totalMs = ms(8, 0),
        targetC = 90,
        speed = 2,
        reverse = false,
        maxSpeedHeating = 3,
    )

    /** Cuisson manuelle arrêtée, encore affichée sur le robot avec son OK. */
    fun fin() = FinState(
        name = tr("Cuisson manuelle"),
        finished = false,
        end = tr("Arrêté"),
        detail = tr("Après %s sur %s", Fmt.duration(3 * 60 + 20), Fmt.duration(8 * 60)),
        settings = listOf(tr("%d °C", 90), tr("vitesse %d", 2)).joinToString(" · "),
        progress = 200f / 480f,
        bowlC = 58,
    )

    fun programme() = ProgrammeState(
        startedAt = "19:02",
        name = tr("Vapeur"),
        bowlC = 100,
        remainingMs = ms(14, 5),
        totalMs = ms(20, 0),
        tags = listOf(tr("%d °C", 120), tr("Lame à l'arrêt")),
        hint = tr("%s d'eau au moins", Fmt.grams(500)),
        programs = listOf(
            ProgramItem(ProgramKind.KNEAD, tr("Pétrir"), tr("%s · sens alternés, vitesse %d", Fmt.duration(45), 4)),
            ProgramItem(ProgramKind.STEAM, tr("Vapeur"), tr("%s · %d °C, lame à l'arrêt", Fmt.duration(20 * 60), 120), running = true),
            ProgramItem(ProgramKind.TOAST, tr("Rissoler"), tr("%s · %d °C, sens inverse", Fmt.duration(7 * 60), 130)),
            ProgramItem(ProgramKind.CLEAN, tr("Nettoyage"), tr("%s · vitesse %d à %d", Fmt.duration(90), 4, 10)),
        ),
    )

    fun repos() = ReposState(
        bowlC = 64,
        scaleG = 0,
        lidClosed = true,
        bowlInPlace = true,
        last = LastActivity(RECIPE, tr("Recette terminée à %s · %s", "12:41", Fmt.duration(36 * 60))),
    )

    fun attente() = AttenteState(
        recipe = RECIPE,
        sentAt = "12:02",
        stepCount = 6,
        durationS = 35 * 60,
        bowlC = 21,
        scaleG = 0,
        lidClosed = true,
        bowlInPlace = true,
    )

    fun recettes() = RecettesState(
        items = listOf(
            RecipeCard("perso-1", RECIPE, 35, "Soupes", personal = true, favorite = true),
            RecipeCard("lidl-101", "Pâte à pizza", 7, "Pâtes"),
            RecipeCard("lidl-102", "Risotto aux champignons", 40, "Plats"),
            RecipeCard("lidl-103", "Compote de pommes", 20, "Desserts"),
            RecipeCard("lidl-104", "Soupe de légumes", 30, "Soupes"),
            RecipeCard("lidl-105", "Crème anglaise", 15, "Desserts"),
        ),
        hasMore = true,
        filters = recipeChips,
    )

    /** Pastilles d'un robot : les fixes, « Mes recettes », « 30 min max » et ses catégories. */
    val recipeChips: List<RecipeChip> = RecipeChip.FIXED + RecipeChip.USER + RecipeChip.QUICK +
        listOf("Accompagnements", "Apéritifs", "Boissons", "Desserts", "Entrées", "Pains et viennoiseries", "Pâtes", "Plats",
            "Sauces", "Soupes").map { RecipeChip.category(it) }

    fun fiche() = FicheState(
        id = "perso-1",
        title = RECIPE,
        personal = true,
        stepCount = 6,
        favorite = true,
        durationMin = 35,
        servings = 4,
        difficulty = "Facile",
        ingredients = listOf(
            IngredientItem("Oignon", "1"),
            IngredientItem("Beurre", Fmt.grams(20)),
            IngredientItem("Potimarron", Fmt.grams(600)),
            IngredientItem("Eau", Fmt.grams(500)),
            IngredientItem("Crème", Fmt.grams(100)),
            IngredientItem("Sel, poivre", ""),
        ),
        // Pas dans la maquette (onglet Étapes) : les étapes de l'écran Étapes.
        steps = listOf(
            FicheStep("Préparer les légumes", tr("À la main")),
            FicheStep("Peser 20 g de beurre", tr("Pesée")),
            FicheStep("Faire revenir l'oignon", Fmt.cooking(3 * 60, 100, 1)),
            FicheStep("Ajouter le potimarron et 500 g d'eau", tr("Pesée")),
            FicheStep("Cuire", Fmt.cooking(20 * 60, 100, 1)),
            FicheStep("Ajouter 100 g de crème, mixer", Fmt.cooking(60, null, 8)),
        ),
        baseServings = 4,
        minServings = 2,
        maxServings = 8,
        totalGrams = 1220,
    )

    /** La fiche pour 6 personnes (quantités du robot pour 6), avec sa notice. */
    fun ficheSix() = fiche().copy(
        servings = 6,
        totalGrams = 1830,
        ingredients = listOf(
            IngredientItem("Oignons", "1 ½"),
            IngredientItem("Beurre", Fmt.grams(30)),
            IngredientItem("Potimarron", Fmt.grams(900)),
            IngredientItem("Eau", Fmt.grams(750)),
            IngredientItem("Crème", Fmt.grams(150)),
            IngredientItem("Sel, poivre", ""),
        ),
    )

    /** Passée à 6 personnes sur un robot plus ancien : les quantités pour 4, estompées, jusqu'à sa réponse. */
    fun ficheEnAttente() = fiche().copy(servings = 6, quantitiesPending = true)

    /** Pour 8 personnes : le bol risque d'être trop rempli. */
    fun ficheHuit() = fiche().copy(
        servings = 8,
        totalGrams = 2640,
        bowlTooFull = true,
        ingredients = listOf(
            IngredientItem("Oignons", "2"),
            IngredientItem("Beurre", Fmt.grams(40)),
            IngredientItem("Potimarron", Fmt.grams(1200)),
            IngredientItem("Eau", Fmt.grams(1000)),
            IngredientItem("Crème", Fmt.grams(200)),
            IngredientItem("Sel, poivre", ""),
        ),
    )

    fun envoyee() = EnvoyeeState(
        id = "perso-1",
        title = RECIPE,
        personal = true,
        stepCount = 6,
        durationMin = 35,
        servings = 4,
        sentAt = "12:02",
        receivedAt = "12:02",
    )

    fun journal() = JournalState(
        days = listOf(
            JournalDay(tr("Aujourd'hui"), listOf(
                JournalEntry("j1", JournalKind.RECIPE, RECIPE, tr("%s – %s · recette, %d étapes", "12:05", "12:41", 6), JournalOutcome.FINISHED,
                    steps = listOf(
                        "Faire revenir" to Fmt.cooking(3 * 60, 100, 1),
                        "Cuire" to Fmt.cooking(21 * 60, 100, 1),
                        "Mixer" to Fmt.cooking(60, null, 8),
                    ),
                    note = tr("Et %d étapes à la main", 3) + " · " + tr("1 minute ajoutée depuis ce téléphone"), recipeId = "perso-1"),
                JournalEntry("j2", JournalKind.MANUAL, tr("Cuisson manuelle"), "11:48 · " + Fmt.cooking(3 * 60, 60, null) + " · " + tr("sans mélange"),
                    JournalOutcome.STOPPED, note = tr("Arrêtée depuis ce téléphone après %s", Fmt.duration(130))),
                JournalEntry("j3", JournalKind.KNEAD, tr("Pétrir"), tr("%s · programme, %s", "09:15", Fmt.duration(45)), JournalOutcome.FINISHED),
            )),
            JournalDay(tr("Hier"), listOf(
                JournalEntry("j4", JournalKind.CLEAN, tr("Nettoyage"), tr("%s · programme, %s", "20:32", Fmt.duration(90)), JournalOutcome.FINISHED),
                JournalEntry("j5", JournalKind.RECIPE, "Pâte à pizza", tr("%s – %s · recette, %d étapes", "18:40", "18:47", 3), JournalOutcome.FINISHED,
                    recipeId = "lidl-101"),
            )),
        ),
    )

    fun robot() = RobotState(
        robotName = "Monsieur Cuisine Connect",
        version = "0.7.0",
        pairMethod = PairMethod.QR,
        pairedOn = "21 novembre",
        // Ce téléphone, sous son nom par défaut, chez un robot qui le laisse se renommer.
        phoneName = tr("Téléphone %d", 1),
        phoneDefault = tr("Téléphone %d", 1),
        canRename = true,
        alerts = setOf(AlertKind.WAITING, AlertKind.STEP_END, AlertKind.PAUSE, AlertKind.TEMP),
        languages = listOf("fr" to "Français", "ca" to "Català", "de" to "Deutsch", "en" to "English", "es" to "Español", "pl" to "Polski", "uk" to "Українська"),
        update = app.toque.companion.update.UpdateView(installed = "0.5.0", phase = app.toque.companion.update.UpdatePhase.UP_TO_DATE, version = "0.5.0"),
    )

    fun appairage() = AppairageState()

    /** Diagnostic d'une soirée : appli quittée, programme lancé sur le robot, alerte de fin. */
    fun diagnostic() = DiagnosticState(
        phone = "Android 14 (API 34), samsung, notifications permises, batterie limitée",
        lines = listOf(
            "22/11 19:02:11  appli quittée",
            "22/11 19:02:11  état n° 412, journal 37, pas de programme",
            "22/11 19:04:13  état n° 413, journal 38, programme MANUAL RUNNING",
            "22/11 19:04:13  journal lu après 37 : 1 ligne(s), 0 alerte(s)",
            "22/11 19:04:13  verrous pris (cuisson en cours)",
            "22/11 19:09:13  état n° 431, journal 39, pas de programme",
            "22/11 19:09:13  journal lu après 38 : 1 ligne(s), 1 alerte(s)",
            "22/11 19:09:13  alerte ligne 39 (end)",
            "22/11 19:09:13  verrous rendus",
            "22/11 19:24:13  réveil par alarme (connected)",
        ).joinToString("\n"),
    )

    /** Pousse tous les exemples dans leurs écrans. */
    /** Éditeur d'une recette écrite sur le téléphone (maquette « La recette »). */
    fun editeur() = EditeurState(
        heading = tr("Nouvelle recette"), phase = EditPhase.EDITING, title = "Soupe de légumes de mamie", servings = 4, minutes = 40, difficulty = 1,
        lines = listOf(EditLine("", "2 carottes"), EditLine("", "500 g d'eau"), EditLine("Pour servir", "10 cl de crème", header = true)),
        steps = listOf(
            EditStepRow("Cuire les légumes dans l'eau.", machine = "20 min · 100 °C · vitesse 1", review = true,
                settingNote = tr("À vérifier sur le robot avant le premier lancement")),
            EditStepRow("Mixer la soupe encore chaude.", machine = "1 min · 90 °C · vitesse 3",
                settingNote = tr("Nouveau réglage : à confirmer une fois sur le robot avant le premier lancement"), typed = true),
            EditStepRow("Ajouter la crème et servir.", grams = 100, settingNote = ""),
        ),
        note = tr("Brouillon gardé sur le téléphone"), dirty = true,
    )

    /** Le robot refuse une étape (maquette « Si le robot refuse une étape ») : le message vient de lui. */
    fun editeurRefus() = editeur().copy(
        heading = tr("Modifier la recette"),
        banner = EditBanner(EditBanner.Kind.REFUSED, tr("Le robot n'a pas enregistré la recette."), tr("Corrigez l'étape %d, puis enregistrez de nouveau.", 3)),
        steps = editeur().steps.mapIndexed { i, st -> if (i == 2) st.copy(grams = 9000, refused = "Étape 3 : Entrez un poids entre 1 et 5000 g") else st },
    )

    fun etape() = EtapeState(1, "Cuire les légumes dans l'eau.", 0, "20 min · 100 °C · vitesse 1", review = true, editable = true,
        settingNote = tr("À vérifier sur le robot avant le premier lancement"))

    /** Robot qui ne prend pas les réglages (avant 0.4.0 du téléphone) : le réglage montré tel quel. */
    fun etapeAncienRobot() = EtapeState(1, "Cuire les légumes dans l'eau.", 0, "20 min · 100 °C · vitesse 1", review = true)

    /** Ce que l'éditeur d'étape d'un Monsieur Cuisine Connect propose (comme le robot l'annonce). */
    private val connect get() = app.toque.core.companion.StepSettingsWire.of(app.toque.core.robot.RobotProfiles.MC_CONNECT)

    /** Feuille « Réglage du robot » : la cuisson manuelle enregistrée, passée de la vitesse 1 à 2. */
    fun etapeReglageManuel(): EtapeState {
        val stored = app.toque.core.recipes.StepMachine(app.toque.core.recipes.StepProgram.MANUAL, 1200, 1, 100)
        val c = app.toque.companion.flows.SettingChoice.start(connect, stored).step(connect, "speed", 1)
        return etape().copy(sheet = app.toque.companion.flows.SettingChoice.state(1, connect, c, stored, stored.summary, canRestore = true))
    }

    /** Feuille « Réglage du robot » : pétrir choisi sur une étape écrite ici. */
    fun etapeReglageProgramme(): EtapeState {
        val c = app.toque.companion.flows.SettingChoice().pick(connect, app.toque.core.recipes.StepProgram.KNEAD).step(connect, "seconds", 1)
        return EtapeState(3, "Pétrir la pâte.", 0, null, editable = true, settingNote = tr("Étape sans le robot : couper, verser, réserver…"),
            sheet = app.toque.companion.flows.SettingChoice.state(3, connect, c, null, null, canRestore = false))
    }

    /** Un réglage tapé ici, refusé par le robot à l'enregistrement : son message, la rangée bordée. */
    fun etapeReglageRefuse() = EtapeState(2, "Mixer la soupe encore chaude.", 0, "1 min · 100 °C · vitesse 5", editable = true,
        refused = "Étape 2 : En chauffe, vitesse 3 maximum", typed = true, settingRefused = true,
        settingNote = tr("Nouveau réglage : à confirmer une fois sur le robot avant le premier lancement"))

    /**
     * Fiche d'une recette Mealie chez un robot qui prend le réglage du propriétaire depuis le téléphone : l'étape 2
     * porte « Mon réglage » (tapé sur le robot), l'étape 3 un réglage envoyé du téléphone, à vérifier sur le robot.
     */
    fun ficheMealieReglages() = fiche().copy(personal = false, mealie = true, category = "Desserts", canWrite = true, canFavorite = true,
        ownSettings = true, canOwn = true, stepCount = 4, steps = listOf(
            FicheStep("Mettre la farine et le beurre dans le bol.", tr("À la main")),
            FicheStep("Pétrir la pâte.", app.toque.core.recipes.StepSettings.default(app.toque.core.recipes.StepProgram.KNEAD).summary, own = "robot"),
            FicheStep("Cuire les pommes en compote.", Fmt.cooking(10 * 60, 90, 1), own = "phone", review = true),
            FicheStep("Mixer la compote.", Fmt.cooking(30, null, 6), review = true),
        ))

    /** « Mon réglage » de l'étape 3 : un réglage envoyé du téléphone, à vérifier sur le robot. */
    fun monReglage() = MonReglageState(3, "Gâteau du verger", "Cuire les pommes en compote.", "10 min · 90 °C · vitesse 1", own = "phone",
        review = true, recipeSetting = "8 min · 90 °C · vitesse 1")

    /** La feuille ouverte sur la cuisson manuelle de l'étape, passée de la vitesse 1 à 2. */
    fun monReglageFeuille(): MonReglageState {
        val now = app.toque.core.recipes.StepMachine(app.toque.core.recipes.StepProgram.MANUAL, 600, 1, 90)
        val c = app.toque.companion.flows.SettingChoice.start(connect, now).step(connect, "speed", 1)
        return monReglage().copy(sheet = app.toque.companion.flows.SettingChoice.state(3, connect, c, now, now.summary, canRestore = false))
    }

    /** Étape sans réglage à soi, au réglage lu dans la recette (à vérifier). */
    fun monReglageRecette() = MonReglageState(4, "Gâteau du verger", "Mixer la compote.", "30 s · vitesse 6", review = true)

    /** Le réglage envoyé, refusé par le robot : son message, la rangée bordée. */
    fun monReglageRefus() = monReglageRecette().copy(machine = "30 s · 90 °C · vitesse 6", refused = "Étape 4 : En chauffe, vitesse 3 maximum",
        settingRefused = true)

    /** Le texte de l'étape a changé sur le robot depuis la lecture de la fiche : relue, rien d'enregistré. */
    fun monReglageChange() = monReglageRecette().copy(text = "Mixer la compote 1 min.",
        refused = tr("La recette a changé sur le robot : relisez l'étape, puis choisissez de nouveau son réglage."))

    fun courses() = CoursesState(
        toBuy = listOf(
            CourseRow(1, "850 g de potimarron", "Velouté de potimarron · Gratin"),
            CourseRow(2, "2 oignons", "Velouté de potimarron"),
            CourseRow(3, "100 g de crème", "Velouté de potimarron"),
            CourseRow(4, "Café"),
        ),
        inCart = listOf(CourseRow(5, "40 g de beurre", "Velouté de potimarron · Gratin", checked = true)),
    )

    fun renderAll(s: Screens) {
        s.repos.render(repos())
        s.main.render(main())
        s.pesee.render(pesee())
        s.pause.render(pause())
        s.reprise.render(reprise())
        s.manuel.render(manuel())
        s.programme.render(programme())
        s.attente.render(attente())
        s.fin.render(fin())
        s.etapes.render(etapes())
        s.recettes.render(recettes())
        s.fiche.render(fiche())
        s.envoyee.render(envoyee())
        s.journal.render(journal())
        s.robot.render(robot())
        s.appairage.render(appairage())
        s.diagnostic.render(diagnostic())
        s.editeur.render(editeur())
        s.etape.render(etape())
        s.reglage.render(monReglage())
        s.courses.render(courses())
    }
}
