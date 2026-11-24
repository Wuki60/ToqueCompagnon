package app.toque.companion

import app.toque.companion.WireFixtures.T
import app.toque.companion.WireFixtures.input
import app.toque.companion.WireFixtures.job
import app.toque.companion.WireFixtures.no
import app.toque.companion.WireFixtures.paused
import app.toque.companion.WireFixtures.ready
import app.toque.companion.WireFixtures.session
import app.toque.companion.WireFixtures.state
import app.toque.companion.WireFixtures.yes
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.live.LiveInput
import app.toque.core.companion.ActivityWire
import app.toque.core.companion.IngredientGroupWire
import app.toque.core.companion.IngredientWire
import app.toque.core.companion.Origin
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.WaitingWire
import app.toque.core.machine.ManualSettings

/** Les écrans « En direct » des captures, rendus depuis des états du fil (préfixe « Fil- »). */
object LiveShots {
    private val down = LinkStatus(LinkPhase.UNREACHABLE, "Monsieur Cuisine Connect")
    private val detail = RecipeDetail(id = "perso-veloute-de-potimarron", ingredients = listOf(IngredientGroupWire(items = listOf(
        IngredientWire("20", "g", "Beurre"), IngredientWire("600", "g", "Potimarron", "en dés"), IngredientWire("1", "", "Oignon"),
        IngredientWire("500", "g", "Eau"), IngredientWire("100", "g", "Crème")))))

    val all: List<Pair<String, () -> LiveInput>> = listOf(
        "Fil-Main" to { input(state(job = job(), session = session(4, "nothing", running = listOf(4)),
            machine = ready.copy(tempC = 96, motorOn = true, heaterOn = true)), ageMs = 400) },
        "Fil-Pesee" to { input(state(session = session(3, "weigh"), machine = ready.copy(weightG = 372))) },
        "Fil-EtapesALaMain" to { input(state(session = session(0, "hand"))).copy(recipe = detail) },
        "Fil-EtapesALancer" to { input(state(session = session(2, "launch"))) },
        "Fil-EtapesMixageChaud" to { input(state(session = session(5, "hot_blend"), machine = ready.copy(tempC = 92, hot = true))) },
        "Fil-EtapesTerminer" to { input(state(session = session(5, "finish", done = (0..5).toList()))) },
        "Fil-Reprise" to {
            val j = paused(job(), origin = Origin())
            input(state(job = j, session = session(4, "resume", running = listOf(4)), can = WireFixtures.can(j, resume = yes)))
        },
        "Fil-PauseRobot" to {
            val j = paused(job(remainingMs = 312_000, totalMs = 480_000, speed = 2, tempC = 90), lidOpened = true)
            input(state(job = j, can = WireFixtures.can(j, resume = no("pause_unknown",
                "La pause n'a pas été vue depuis son début : reprenez sur le robot"))))
        },
        "Fil-Manuel" to { input(state(job = job(remainingMs = 312_000, totalMs = 480_000, speed = 2, tempC = 90), machine = ready.copy(tempC = 86))) },
        // Fin d'un programme encore affichée sur le robot (son OK) : arrêt, fin normale bol chaud, raisons longues.
        "Fil-FinArrete" to { input(state(job = job(state = "ABORTED", endReason = "Arrêté", remainingMs = 280_000, totalMs = 480_000,
            speed = 2, tempC = 90), machine = ready.copy(tempC = 58))) },
        "Fil-FinTermineBolChaud" to { input(state(job = job(state = "FINISHED", endReason = "Terminé", remainingMs = 0, totalMs = 480_000,
            speed = 2, tempC = 90, reverse = true), machine = ready.copy(tempC = 86, hot = true))) },
        "Fil-FinAnnule" to { input(state(job = job(state = "ABORTED", endReason = "Pause trop longue : programme annulé",
            remainingMs = 900_000, totalMs = 1_200_000, speed = 1, tempC = 100), machine = ready.copy(tempC = 71, hot = true))) },
        "Fil-FinAlerte" to { input(state(job = job(kind = "STEAM", label = "Vapeur", kindLabel = "Vapeur", state = "ABORTED",
            endReason = "Erreur de la carte moteur : la base a signalé un défaut", remainingMs = 845_000, totalMs = 1_200_000,
            speed = 0, tempC = 120), machine = ready.copy(tempC = 92, hot = true))) },
        "Fil-EtapesArretee" to { input(state(job = job(state = "ABORTED", endReason = "Pause trop longue : programme annulé",
            remainingMs = 900_000, totalMs = 1_200_000), session = session(4, "launch"))) },
        "Fil-ManuelFouet" to { input(state(job = job(remainingMs = 150_000, totalMs = 240_000, speed = 3, tempC = 0, accessory = "WHISK"),
            machine = ready.copy(tempC = 24))) },
        "Fil-Programme" to { input(state(job = job(kind = "STEAM", label = "Vapeur", kindLabel = "Vapeur", remainingMs = 845_000,
            totalMs = 1_200_000, speed = 0, tempC = 120), machine = ready.copy(tempC = 100))) },
        "Fil-Repos" to { input(state(machine = ready.copy(tempC = 64), last = ActivityWire(3, "recipe", "Velouté de potimarron",
            startedAt = T - 3_600_000, endedAt = T - 1_440_000, outcome = "finished"))) },
        "Fil-ReposLancementRefuse" to { input(state(machine = ready.copy(tempC = 24, lidOpen = true),
            can = WireFixtures.can(null).copy(start = no("lid", "Fermez le couvercle sur le robot")))) },
        "Fil-ManuelPreparation" to { input(state(machine = ready.copy(tempC = 24))).copy(prep = ManualSettings()) },
        // 100 °C demandés à vitesse 5 : la vitesse est ramenée à 3, comme sur le robot.
        "Fil-ManuelPreparationChauffe" to { input(state(machine = ready.copy(tempC = 24))).copy(prep =
            (1..13).fold(ManualSettings(seconds = 600, speed = 5)) { s, _ -> s.change(ManualSettings.CARD_TEMP, 1).settings }) },
        "Fil-ManuelPreparationBolChaud" to { input(state(machine = ready.copy(tempC = 72, hot = true)))
            .copy(prep = ManualSettings(seconds = 30, speed = 6)) },
        "Fil-Attente" to { input(state(waiting = WaitingWire("perso-veloute-de-potimarron", "Velouté de potimarron", 6, 35, 4,
            sentAt = T - 600_000))) },
        "Fil-HorsLigne" to { input(state(job = job(), session = session(4, "nothing", running = listOf(4))), link = down,
            ageMs = 150_000, offlineForMs = 140_000) },
        "Fil-HorsLigneProgramme" to { input(state(job = paused(job(kind = "STEAM", label = "Vapeur", kindLabel = "Vapeur",
            remainingMs = 845_000, totalMs = 1_200_000, speed = 0, tempC = 120))), link = down, ageMs = 40_000, offlineForMs = 30_000) },
        "Fil-HorsLigneRepos" to { input(state(), link = down, ageMs = 40_000, offlineForMs = 30_000) },
        "Fil-HorsLigneRetire" to { input(null, link = down.copy(maybeRemoved = true), offlineForMs = 60_000) },
        "Fil-Connexion" to { input(null, link = LinkStatus(LinkPhase.CONNECTING, "Monsieur Cuisine Connect")) },
        "Fil-AlerteMachine" to { input(state(job = job(), machine = ready.copy(ready = false, faultCode = "BOARD_ERROR",
            faultMessage = "Erreur de la carte moteur : la base a signalé un défaut"))) },
        "Fil-BasePasPrete" to { input(state(machine = ready.copy(link = "LOST", linkLabel = "Liaison perdue", ready = false))) },
    )
}
