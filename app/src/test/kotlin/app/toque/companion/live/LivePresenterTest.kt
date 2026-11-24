package app.toque.companion.live

import app.toque.companion.WireFixtures
import app.toque.companion.WireFixtures.T
import app.toque.companion.WireFixtures.input
import app.toque.companion.WireFixtures.job
import app.toque.companion.WireFixtures.no
import app.toque.companion.WireFixtures.paused
import app.toque.companion.WireFixtures.ready
import app.toque.companion.WireFixtures.session
import app.toque.companion.WireFixtures.state
import app.toque.companion.WireFixtures.steps
import app.toque.companion.WireFixtures.yes
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.ui.Link
import app.toque.companion.ui.Route
import app.toque.companion.ui.StepStatus
import app.toque.companion.ui.screens.AttenteState
import app.toque.companion.ui.screens.EtapesState
import app.toque.companion.ui.screens.FinState
import app.toque.companion.ui.screens.IncidentKind
import app.toque.companion.ui.screens.IncidentState
import app.toque.companion.ui.screens.MainState
import app.toque.companion.ui.screens.ManuelState
import app.toque.companion.ui.screens.PauseCause
import app.toque.companion.ui.screens.PauseState
import app.toque.companion.ui.screens.PeseeState
import app.toque.companion.ui.screens.ProgramKind
import app.toque.companion.ui.screens.ProgrammeState
import app.toque.companion.ui.screens.ReposState
import app.toque.companion.ui.screens.RepriseState
import app.toque.core.companion.ActivityWire
import app.toque.core.companion.IngredientGroupWire
import app.toque.core.companion.IngredientWire
import app.toque.core.companion.Origin
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.StepWire
import app.toque.core.companion.WaitingWire
import app.toque.core.companion.WeighWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chaque règle du choix d'écran et du contenu, à partir d'objets du fil. */
class LivePresenterTest {
    private fun present(i: LiveInput) = LivePresenter.present(i)

    // ------------------------------------------------------------------ rien en cours

    @Test fun idleRobotIsResting() {
        val last = ActivityWire(3, "recipe", "Pâte à pizza", startedAt = T - 3_600_000, endedAt = T - 3_000_000, outcome = "finished")
        val v = present(input(state(machine = ready.copy(tempC = 64, weightG = 0), last = last)))
        assertEquals(Route.REPOS, v.route)
        val s = v.screen as ReposState
        assertEquals(64, s.bowlC)
        assertTrue(s.lidClosed && s.bowlInPlace)
        assertEquals("Pâte à pizza", s.last!!.title)
        assertEquals("Recette terminée à 11:10 · 10 min", s.last!!.detail)
        assertNull(v.etapes)
    }

    @Test fun anOlderRobotWithoutClearKeepsTheRestingScreenAfterAnEnd() {
        val j = job(state = "ABORTED", endReason = "Arrêté")
        val v = present(input(state(job = j, can = WireFixtures.olderRobot(WireFixtures.can(j)), last = ActivityWire(1, "manual",
            "Cuisson manuelle", startedAt = T - 200_000, endedAt = T - 60_000, outcome = "stopped"))))
        assertEquals(Route.REPOS, v.route)
        assertEquals("Arrêté à 11:59 · 2 min %02d".format(20), (v.screen as ReposState).last!!.detail)
        // Pas de can.clear dans l'état reçu : refusé, sans code.
        assertFalse(state(job = j, can = WireFixtures.olderRobot(WireFixtures.can(j))).can.clear.allowed)
        assertNull(LivePresenter.endedJob(state(job = j, can = WireFixtures.olderRobot(WireFixtures.can(j)))))
        // Avec une recette guidée non plus, pas de barre de fin.
        val withRecipe = present(input(state(job = j, session = session(4, "launch"), can = WireFixtures.olderRobot(WireFixtures.can(j)))))
        assertNull(withRecipe.etapes!!.now)
    }

    // ------------------------------------------------------------------ fin d'un programme (OK)

    @Test fun aStoppedManualCookingShowsTheEndScreenLikeTheRobotBar() {
        val j = job(state = "ABORTED", endReason = "Arrêté", remainingMs = 280_000, totalMs = 480_000, speed = 2, tempC = 90)
        val v = present(input(state(job = j, machine = ready.copy(tempC = 58))))
        assertEquals(Route.FIN, v.route)
        val f = v.screen as FinState
        assertEquals("Cuisson manuelle", f.name)
        assertFalse(f.finished)
        assertEquals("Arrêté", f.end)
        assertEquals("Après 3 min 20 sur 8 min", f.detail)
        assertEquals("90 °C · vitesse 2", f.settings)
        assertEquals(200f / 480f, f.progress, 0.001f)
        assertEquals(58, f.bowlC)
        assertFalse(f.hot)
        assertTrue(f.canOk)
        assertNull(v.etapes)
        // Réponse attendue : OK grisé.
        assertFalse((present(input(state(job = j), pending = setOf(Pending.CLEAR))).screen as FinState).canOk)
    }

    @Test fun aFinishedJobShowsTheTotalAndWarnsWhenTheBowlIsHot() {
        val j = job(state = "FINISHED", endReason = "Terminé", remainingMs = 0, totalMs = 480_000, speed = 2, tempC = 90, reverse = true)
        val f = present(input(state(job = j, machine = ready.copy(tempC = 86, hot = true)))).screen as FinState
        assertTrue(f.finished)
        assertEquals("Terminé", f.end)
        assertEquals("8 min au total", f.detail)
        assertEquals("90 °C · vitesse 2 · sens inverse", f.settings)
        assertEquals(1f, f.progress, 0f)
        assertTrue(f.hot)
    }

    @Test fun theRobotsEndReasonIsShownAsIs() {
        val reason = "Pause trop longue : programme annulé"
        val f = present(input(state(job = job(state = "ABORTED", endReason = reason, speed = 0, tempC = 100)))).screen as FinState
        assertEquals(reason, f.end)
        assertEquals("100 °C · sans mélange", f.settings)
        // Un programme : son nom, pas de réglages de phase.
        val steam = job(kind = "STEAM", label = "Vapeur", kindLabel = "Vapeur", state = "ABORTED", endReason = "Arrêté", speed = 0, tempC = 120)
        val p = present(input(state(job = steam))).screen as FinState
        assertEquals("Vapeur", p.name)
        assertEquals("", p.settings)
        // Raison vide (robot qui ne la donne pas) : « Arrêté ».
        assertEquals("Arrêté", (present(input(state(job = job(state = "ABORTED")))).screen as FinState).end)
    }

    @Test fun theEndScreenComesAfterAlertsAndBeforePreparationWaitingAndRest() {
        val j = job(state = "ABORTED", endReason = "Arrêté")
        // Alerte machine et base pas prête d'abord.
        assertEquals(Route.INCIDENT, present(input(state(job = j, machine = ready.copy(ready = false, faultCode = "BOARD_ERROR",
            faultMessage = "x")))).route)
        assertEquals(Route.INCIDENT, present(input(state(job = j, machine = ready.copy(link = "LOST", ready = false)))).route)
        // Avant la préparation d'une cuisson manuelle et la recette en attente.
        assertEquals(Route.FIN, present(input(state(job = j)).copy(prep = app.toque.core.machine.ManualSettings())).route)
        val w = WaitingWire("perso-veloute-de-potimarron", "Velouté de potimarron", 6, 35, 4, sentAt = T - 120_000)
        assertEquals(Route.FIN, present(input(state(job = j, waiting = w))).route)
        // OK pressé (sur le robot ou ici) : job null, retour aux écrans habituels.
        assertEquals(Route.REPOS, present(input(state())).route)
        assertEquals(Route.ATTENTE, present(input(state(waiting = w))).route)
        // Un programme en cours passe toujours avant.
        assertEquals(Route.MANUEL, present(input(state(job = job()))).route)
    }

    @Test fun anEndedJobIsNotInProgress() {
        val ended = state(job = job(state = "ABORTED", endReason = "Arrêté"))
        assertFalse(LivePresenter.inProgress(ended))
        assertFalse(LivePresenter.keepFollowing(ended))
        assertFalse(LivePresenter.inProgress(state(job = job(state = "FINISHED"))))
    }

    @Test fun inARecipeAStoppedStepGetsAnEndedBarWithOk() {
        val j = job(state = "ABORTED", endReason = "Pause trop longue : programme annulé")
        val v = present(input(state(job = j, session = session(4, "launch"))))
        assertEquals(Route.ETAPES, v.route)
        val now = v.etapes!!.now!!
        assertEquals("Cuisson manuelle", now.action)
        assertEquals("Pause trop longue : programme annulé", now.end)
        assertTrue(now.canOk)
        assertFalse(present(input(state(job = j, session = session(4, "launch")), pending = setOf(Pending.CLEAR))).etapes!!.now!!.canOk)
        // Une étape finie : rien de nouveau (le robot l'efface seul).
        val done = present(input(state(job = job(state = "FINISHED", endReason = "Terminé"), session = session(5, "launch"))))
        assertEquals(Route.ETAPES, done.route)
        assertNull(done.etapes!!.now)
        // Pesée : l'écran Pesée reste, la barre de fin vit dans les étapes.
        val weigh = present(input(state(job = j, session = session(3, "weigh"))))
        assertEquals(Route.PESEE, weigh.route)
        assertNotNull(weigh.etapes!!.now!!.end)
    }

    @Test fun waitingRecipeShowsAttenteWithThePhoneClock() {
        val w = WaitingWire("perso-veloute-de-potimarron", "Velouté de potimarron", 6, 35, 4, sentAt = T - 120_000)
        // Le robot avance de 5 min sur le téléphone : l'heure d'envoi est ramenée à l'horloge du téléphone.
        val s0 = state(waiting = w, machine = ready.copy(bowlRemoved = true, bowlDetected = false, lidOpen = true))
        val skewed = s0.copy(serverTime = T + 300_000, waiting = w.copy(sentAt = T + 180_000))
        val v = present(input(skewed))
        assertEquals(Route.ATTENTE, v.route)
        val a = v.screen as AttenteState
        assertEquals("11:58", a.sentAt)
        assertEquals(35 * 60, a.durationS)
        assertFalse(a.lidClosed)
        assertFalse(a.bowlInPlace)
    }

    // ------------------------------------------------------------------ recette guidée

    @Test fun runningStepIsMainWithTheStepsOwnText() {
        val j = job()
        val v = present(input(state(job = j, session = session(4, "nothing", running = listOf(4)), machine = ready.copy(tempC = 96, motorOn = true))))
        assertEquals(Route.MAIN, v.route)
        val m = v.screen as MainState
        assertEquals("Velouté de potimarron", m.recipe)
        assertEquals(4, m.stepIndex)
        assertEquals(6, m.stepCount)
        // Pas de titre d'étape inventé ; « Cuisson manuelle » ne dirait rien : seule la pastille des réglages.
        assertEquals("", m.action)
        assertEquals(steps[4].text, m.instruction)
        assertEquals("20 min · 100 °C · vitesse 1", m.settings)
        assertEquals(760_000, m.remainingMs)
        assertEquals(100, m.targetC)
        assertTrue(m.lidLocked)
        // L'étape suivante est une étape machine : pas de « Ensuite, à la main ».
        assertNull(m.nextByHand)
        assertTrue(m.canAddMinute && m.canPause && m.canStop)
        val e = v.etapes!!
        assertFalse(e.root)
        assertEquals(StepStatus.CURRENT, e.steps[4].status)
        assertNotNull(e.steps[4].progress)
        assertEquals(StepStatus.DONE, e.steps[3].status)
        assertEquals("Contenu chaud : à confirmer sur le robot", e.steps[5].warning)
        assertEquals("Cuisson manuelle", e.now!!.action)
    }

    @Test fun programStepIsNamed() {
        val v = present(input(state(job = job(kind = "KNEAD", kindLabel = "Pétrir", label = "Pétrir", speed = 4, tempC = 0),
            session = session(4, "nothing", running = listOf(4)))))
        assertEquals("Pétrir", (v.screen as MainState).action)
    }

    @Test fun sentRecipeKeepsTheFollowingForHalfAnHour() {
        val w = WaitingWire("perso-veloute-de-potimarron", "Velouté de potimarron", 6, 35, 4, sentAt = T - 29 * 60_000L)
        assertFalse(LivePresenter.inProgress(state(waiting = w)))
        assertTrue(LivePresenter.keepFollowing(state(waiting = w)))
        assertFalse(LivePresenter.keepFollowing(state(waiting = w.copy(sentAt = T - 31 * 60_000L))))
        assertTrue(LivePresenter.keepFollowing(state(job = job())))
        assertFalse(LivePresenter.keepFollowing(state()))
    }

    @Test fun nextHandStepIsShown() {
        val s2 = steps.toMutableList()
        s2[5] = StepWire(5, "Ajouter 100 g de crème.")
        val v = present(input(state(job = job(), session = session(4, "nothing", running = listOf(4), steps = s2))))
        assertEquals("Ajouter 100 g de crème.", (v.screen as MainState).nextByHand)
    }

    @Test fun remainingTimeMovesOnlyWhileCounting() {
        val running = present(input(state(job = job(), session = session(4, "nothing", running = listOf(4))), ageMs = 10_400))
        assertEquals(749_600, (running.screen as MainState).remainingMs)
        assertEquals(749_600, running.etapes!!.now!!.remainingMs)
        val starting = present(input(state(job = job(state = "STARTING"), session = session(4, "nothing", running = listOf(4))), ageMs = 10_000))
        assertEquals(760_000, (starting.screen as MainState).remainingMs)
        // Jamais négatif.
        val late = present(input(state(job = job(remainingMs = 2_000), session = session(4, "nothing", running = listOf(4))), ageMs = 60_000))
        assertEquals(0, (late.screen as MainState).remainingMs)
    }

    @Test fun forbiddenOrPendingButtonsAreDisabled() {
        val j = job(state = "STOPPING")
        val can = WireFixtures.can(j).copy(pause = no("nothing_running", "Aucun programme en cours"), adjust = no("fault", "Alerte"))
        val v = present(input(state(job = j, session = session(4, "nothing", running = listOf(4)), can = can), pending = setOf(Pending.STOP)))
        val m = v.screen as MainState
        assertFalse(m.canPause)
        assertFalse(m.canAddMinute)
        assertFalse(m.canStop)
    }

    @Test fun weighingStepIsPesee() {
        val v = present(input(state(session = session(3, "weigh"), machine = ready.copy(weightG = 372))))
        assertEquals(Route.PESEE, v.route)
        val p = v.screen as PeseeState
        assertEquals(steps[3].text, p.instruction)
        assertEquals(372, p.weightG)
        assertEquals(500, p.targetG)
        assertEquals(listOf("Eau"), p.items.map { it.name })
        // Ensuite : l'étape machine suivante, nommée par son programme, avec les réglages du robot.
        assertEquals("Cuisson manuelle", p.nextAction)
        assertEquals("20 min · 100 °C · vitesse 1", p.nextSettings)
    }

    @Test fun consecutiveWeighingsAreListed() {
        val s2 = listOf(
            StepWire(0, "Peser le potimarron.", weigh = WeighWire("Potimarron en dés", 600, 604)),
            StepWire(1, "Ajouter l'eau.", weigh = WeighWire("Eau", 500)),
            StepWire(2, "Mixer.", hotBlendOnRobot = false),
        )
        val p = present(input(state(session = session(1, "weigh", steps = s2), machine = ready.copy(weightG = 372)))).screen as PeseeState
        assertEquals(listOf("Potimarron en dés", "Eau"), p.items.map { it.name })
        assertTrue(p.items[0].done)
        assertEquals(604, p.items[0].weightG)
        assertFalse(p.items[1].done)
        // L'étape suivante est à la main : pas de carte « Cette étape se lance sur le robot ».
        assertNull(p.nextAction)
    }

    @Test fun robotWaitingOnSomethingElseShowsTheStepsAsRoot() {
        val cases = mapOf(
            0 to ("hand" to "À faire à la main, puis « Suivant » sur le robot."),
            2 to ("launch" to "Cette étape se lance sur le robot"),
            5 to ("hot_blend" to "Contenu chaud : confirmez le mixage sur le robot"),
        )
        for ((index, c) in cases) {
            val v = present(input(state(session = session(index, c.first))))
            assertEquals(c.first, Route.ETAPES, v.route)
            val e = v.screen as EtapesState
            assertTrue(e.root)
            val cur = e.steps[index]
            assertEquals(StepStatus.CURRENT, cur.status)
            assertEquals(steps[index].text, cur.title)
            assertEquals(c.second, cur.waiting)
            assertNull(cur.progress)
            assertNull(e.now)
            assertTrue(e.steps.filterIndexed { i, _ -> i != index }.all { it.waiting == null })
        }
        val fin = present(input(state(session = session(5, "finish", done = (0..5).toList())))).screen as EtapesState
        assertEquals(StepStatus.CURRENT, fin.steps[5].status)
        assertEquals("Dernière étape faite : terminez sur le robot", fin.steps[5].waiting)
    }

    @Test fun ingredientsComeFromTheRecipeDetail() {
        val detail = RecipeDetail(id = "perso-veloute-de-potimarron", ingredients = listOf(IngredientGroupWire(items = listOf(
            IngredientWire("20", "g", "Beurre"), IngredientWire("1", "", "Oignon", "émincé")))))
        val v = present(input(state(session = session(0, "hand"))).copy(recipe = detail))
        val e = v.screen as EtapesState
        assertEquals(listOf("Beurre" to "20 g", "Oignon, émincé" to "1"), e.ingredients.map { it.name to it.quantity })
        // Une autre recette : pas ses ingrédients.
        val other = present(input(state(session = session(0, "hand"))).copy(recipe = detail.copy(id = "autre")))
        assertTrue((other.screen as EtapesState).ingredients.isEmpty())
    }

    // ------------------------------------------------------------------ pauses

    @Test fun pauseFromThisPhoneThatMayResumeIsReprise() {
        val j = paused(job())
        val v = present(input(state(job = j, session = session(4, "resume", running = listOf(4)),
            can = WireFixtures.can(j, resume = yes)), ageMs = 2_000))
        assertEquals(Route.REPRISE, v.route)
        val r = v.screen as RepriseState
        assertEquals("Velouté de potimarron", r.recipe)
        assertNull(r.pausedBy)
        assertTrue(r.canResume)
        assertEquals(760_000, r.remainingMs)
        assertEquals(550_000L, r.autoCancelMs)
        assertTrue(v.etapes!!.now!!.paused)
        val pending = present(input(state(job = j, session = session(4, "resume", running = listOf(4)),
            can = WireFixtures.can(j, resume = yes)), pending = setOf(Pending.RESUME)))
        assertFalse((pending.screen as RepriseState).canResume)
    }

    @Test fun pauseFromTheRobotSaysSo() {
        val j = paused(job(), origin = Origin())
        val r = present(input(state(job = j, can = WireFixtures.can(j, resume = yes)))).screen as RepriseState
        assertEquals("Pause sur le robot", r.pausedBy)
        assertNull(r.recipe)
        val j2 = paused(job(), origin = Origin(Origin.PHONE, "abcdef01", "Téléphone 2"))
        assertEquals("Pause depuis Téléphone 2", (present(input(state(job = j2, can = WireFixtures.can(j2, resume = yes)))).screen as RepriseState).pausedBy)
    }

    @Test fun pauseThatOnlyTheRobotCanResumeIsPause() {
        val lid = paused(job(), reason = "LID", label = "Couvercle ouvert")
        val v = present(input(state(job = lid, session = session(4, "resume", running = listOf(4)), machine = ready.copy(lidOpen = true),
            can = WireFixtures.can(lid, resume = no("lid_opened", "Le couvercle a été ouvert : reprenez sur le robot")))))
        assertEquals(Route.PAUSE, v.route)
        assertEquals(PauseCause.LID, (v.screen as PauseState).cause)

        val bowl = paused(job(), reason = "LID", bowl = true)
        val b = present(input(state(job = bowl, can = WireFixtures.can(bowl, resume = no("bowl_removed", "Bol retiré")))))
        assertEquals(PauseCause.BOWL, (b.screen as PauseState).cause)
        assertNull((b.screen as PauseState).recipe)

        // Lid opened during a user pause, lid closed again since.
        val during = paused(job(), lidOpened = true)
        val d = present(input(state(job = during, can = WireFixtures.can(during,
            resume = no("lid_opened_during_pause", "Le couvercle a été ouvert pendant la pause : reprenez sur le robot")))))
        assertEquals(PauseCause.LID, (d.screen as PauseState).cause)

        val unseen = paused(job(), lidOpened = true)
        val reason = "La pause n'a pas été vue depuis son début : reprenez sur le robot"
        val u = present(input(state(job = unseen, can = WireFixtures.can(unseen, resume = no("pause_unknown", reason))))).screen as PauseState
        assertEquals(PauseCause.ROBOT, u.cause)
        assertEquals(reason, u.reason)
        assertEquals("En pause", u.label)
    }

    // ------------------------------------------------------------------ sans recette

    @Test fun manualCookingIsManuel() {
        val j = job(remainingMs = 312_000, totalMs = 480_000, speed = 2, tempC = 90, reverse = true)
        val v = present(input(state(job = j, machine = ready.copy(tempC = 86))))
        assertEquals(Route.MANUEL, v.route)
        val m = v.screen as ManuelState
        assertEquals("11:52", m.startedAt)
        assertEquals(86, m.bowlC)
        assertEquals(90, m.targetC)
        assertEquals(2, m.speed)
        assertTrue(m.reverse)
        assertEquals(3, m.maxSpeedHeating)
        assertFalse(m.whisk)
        assertTrue(m.canAdjust)
        // Une demande en route ne grise plus les réglages : les appuis s'accumulent (ManualTuner).
        assertTrue((present(input(state(job = j), pending = setOf(Pending.ADJUST))).screen as ManuelState).canAdjust)
        val fixed = present(input(state(job = j, can = WireFixtures.can(j).copy(adjust = no("fault", "x"))))).screen as ManuelState
        assertFalse(fixed.canAdjust)
        // Début inconnu : pas d'heure.
        assertEquals("", (present(input(state(job = j.copy(startedAt = 0)))).screen as ManuelState).startedAt)
    }

    @Test fun pausedManualHasNoRecipeHeader() {
        val j = paused(job(remainingMs = 312_000, totalMs = 480_000))
        val v = present(input(state(job = j, can = WireFixtures.can(j, resume = yes))))
        assertEquals(Route.REPRISE, v.route)
        assertNull((v.screen as RepriseState).recipe)
        assertNull(v.etapes)
    }

    /** Smart : un programme de l'appli d'origine (Smoothie) est reconnu, et la liste montre les programmes du Smart. */
    @Test fun smartProgramIsProgramme() {
        val j = job(kind = "PROGRAM", label = "Smoothie", kindLabel = "Programme", remainingMs = 40_000, totalMs = 60_000, speed = 10, tempC = 0)
        val s = state(job = j).copy(robot = state().robot.copy(name = "Monsieur Cuisine Smart"),
            limits = app.toque.companion.WireFixtures.limits.copy(heatNoStir = false))
        val v = present(input(s))
        assertEquals(Route.PROGRAMME, v.route)
        val p = v.screen as ProgrammeState
        assertEquals("Smoothie", p.name)
        assertEquals(listOf(ProgramKind.SMOOTHIE), p.programs.filter { it.running }.map { it.kind })
        assertEquals(ProgramKind.values().toList(), p.programs.map { it.kind })
        assertEquals("1 min · vitesse 10", p.programs.first { it.kind == ProgramKind.SMOOTHIE }.detail)
        assertEquals("6 h 00 · de 40 à 85 °C", p.programs.first { it.kind == ProgramKind.SOUS_VIDE }.detail)
        // Le Connect garde ses quatre programmes.
        val c = present(input(state(job = job(kind = "STEAM", label = "Vapeur", kindLabel = "Vapeur", totalMs = 1_200_000))))
        assertEquals(4, (c.screen as ProgrammeState).programs.size)
    }

    @Test fun programIsProgramme() {
        val j = job(kind = "STEAM", label = "Vapeur", kindLabel = "Vapeur", remainingMs = 845_000, totalMs = 1_200_000, speed = 0, tempC = 120)
        val v = present(input(state(job = j, machine = ready.copy(tempC = 100))))
        assertEquals(Route.PROGRAMME, v.route)
        val p = v.screen as ProgrammeState
        assertEquals("Vapeur", p.name)
        assertEquals(listOf("120 °C", "Lame à l'arrêt"), p.tags)
        assertEquals(listOf(ProgramKind.STEAM), p.programs.filter { it.running }.map { it.kind })
        // Les programmes décrits d'après leurs définitions du robot.
        assertEquals(listOf("45 s · sens alternés, vitesse 4", "20 min · 120 °C, lame à l'arrêt",
            "7 min · 130 °C, sens inverse", "1 min 30 · vitesse 4 à 10"), p.programs.map { it.detail })

        val clean = job(kind = "MANUAL", label = "Nettoyage", phaseCount = 4, speed = 4, tempC = 0, remainingMs = 60_000, totalMs = 90_000)
        val c = present(input(state(job = clean)))
        assertEquals(Route.PROGRAMME, c.route)
        assertEquals(listOf(ProgramKind.CLEAN), (c.screen as ProgrammeState).programs.filter { it.running }.map { it.kind })
    }

    // ------------------------------------------------------------------ incidents

    @Test fun machineFaultComesFirst() {
        val j = job()
        val v = present(input(state(job = j, session = session(4, "nothing", running = listOf(4)),
            machine = ready.copy(ready = false, faultCode = "BOARD_ERROR", faultMessage = "Erreur de la carte moteur"))))
        assertEquals(Route.INCIDENT, v.route)
        val s = v.screen as IncidentState
        assertEquals(IncidentKind.FAULT, s.kind)
        assertEquals("Erreur de la carte moteur", s.title)
        assertTrue(s.canStop)
        assertNotNull(v.etapes)
        val idle = present(input(state(machine = ready.copy(faultCode = "COMM_LOST")))).screen as IncidentState
        assertEquals("COMM_LOST", idle.title)
        assertFalse(idle.canStop)
    }

    @Test fun baseNotReady() {
        val v = present(input(state(machine = ready.copy(link = "ARMING", linkLabel = "Préparation", ready = false))))
        val s = v.screen as IncidentState
        assertEquals(IncidentKind.BASE, s.kind)
        assertEquals("Préparation", s.chip)
    }

    @Test fun unreachableRobot() {
        val s = state(job = job(), session = session(4, "nothing", running = listOf(4)))
        val down = LinkStatus(LinkPhase.UNREACHABLE, "Monsieur Cuisine Connect")
        // Un hoquet du Wi-Fi : l'écran reste, la pastille dit « Hors ligne ».
        val blip = present(input(s, link = down, ageMs = 3_000, offlineForMs = 2_000))
        assertEquals(Route.MAIN, blip.route)
        assertEquals(Link.OFFLINE, (blip.screen as MainState).link)
        val long = present(input(s, link = down, offlineForMs = LivePresenter.OFFLINE_GRACE_MS))
        assertEquals(Route.INCIDENT, long.route)
        val st = long.screen as IncidentState
        assertEquals(IncidentKind.OFFLINE, st.kind)
        assertEquals(Link.OFFLINE, st.link)
        assertTrue(st.message.contains("Monsieur Cuisine Connect"))
        val removed = present(input(null, link = down.copy(maybeRemoved = true))).screen as IncidentState
        assertTrue(removed.note!!.contains("appairer de nouveau"))
    }

    @Test fun offlineKeepsTheLastKnownJob() {
        val down = LinkStatus(LinkPhase.UNREACHABLE, "Monsieur Cuisine Connect")
        // Recette guidée, étape 5 sur 9 en cuisson : titre, étape, temps restant de ce moment-là, âge.
        val s = state(job = job(remainingMs = 380_000), session = session(4, "nothing", running = listOf(4)))
        val st = present(input(s, link = down, ageMs = 150_000, offlineForMs = 140_000)).screen as IncidentState
        assertTrue(st.canRetry)
        assertFalse(st.canStop)
        val k = st.lastKnown!!
        assertEquals("Velouté de potimarron", k.title)
        assertEquals("Recette · étape 5 sur ${WireFixtures.steps.size}", k.step)
        // Pas de compte à rebours : la valeur reçue, dite au passé.
        assertEquals("Il restait 6 min 20 au dernier contact", k.status)
        assertEquals("Dernier état reçu il y a 2 min", k.heard)
        val later = present(input(s, link = down, ageMs = 600_000, offlineForMs = 590_000)).screen as IncidentState
        assertEquals("Il restait 6 min 20 au dernier contact", later.lastKnown!!.status)
        assertEquals("Dernier état reçu il y a 10 min", later.lastKnown!!.heard)

        // Programme en pause, sans recette.
        val p = state(job = paused(job(kind = "STEAM", label = "Vapeur", kindLabel = "Vapeur")))
        val pk = (present(input(p, link = down, ageMs = 20_000, offlineForMs = LivePresenter.OFFLINE_GRACE_MS)).screen as IncidentState).lastKnown!!
        assertEquals("Vapeur", pk.title)
        assertNull(pk.step)
        assertEquals("En pause au dernier contact", pk.status)
        assertEquals("Dernier état reçu il y a moins d'une minute", pk.heard)

        // Recette qui attend le cuisinier (pas de programme) : titre et étape seulement.
        val w = (present(input(state(session = session(2, "hand")), link = down, offlineForMs = 60_000)).screen as IncidentState).lastKnown!!
        assertEquals("Recette · étape 3 sur ${WireFixtures.steps.size}", w.step)
        assertNull(w.status)

        // Rien en cours, ou aucun état reçu : pas de carte, mais « Réessayer ».
        val idle = present(input(state(), link = down, offlineForMs = 60_000)).screen as IncidentState
        assertNull(idle.lastKnown)
        assertTrue(idle.canRetry)
        val none = present(input(null, link = down)).screen as IncidentState
        assertNull(none.lastKnown)
        assertTrue(none.canRetry)
        // Programme terminé encore affiché : rien « en cours » à garder.
        assertNull(LivePresenter.lastKnown(state(job = job(state = "FINISHED")), 0))
        // Connexion, alerte machine : ni carte ni bouton.
        assertFalse((present(input(null, link = LinkStatus(LinkPhase.CONNECTING))).screen as IncidentState).canRetry)
    }

    @Test fun connectingWithoutState() {
        val v = present(input(null, link = LinkStatus(LinkPhase.CONNECTING, "Monsieur Cuisine Connect")))
        assertEquals(IncidentKind.CONNECTING, (v.screen as IncidentState).kind)
        assertEquals(Link.CONNECTING, (v.screen as IncidentState).link)
    }

    @Test fun inProgress() {
        assertFalse(LivePresenter.inProgress(null))
        assertFalse(LivePresenter.inProgress(state()))
        assertFalse(LivePresenter.inProgress(state(job = job(state = "FINISHED"))))
        assertTrue(LivePresenter.inProgress(state(job = job())))
        assertTrue(LivePresenter.inProgress(state(session = session(0, "hand"))))
    }

    @Test fun temperatureSteps() {
        val t = WireFixtures.limits.temperaturesC
        assertEquals(37, LiveController.nextTemperature(0, 1, t))
        assertNull(LiveController.nextTemperature(0, -1, t))
        assertEquals(0, LiveController.nextTemperature(37, -1, t))
        assertEquals(95, LiveController.nextTemperature(90, 1, t))
        assertEquals(85, LiveController.nextTemperature(90, -1, t))
        assertNull(LiveController.nextTemperature(130, 1, t))
    }
}
