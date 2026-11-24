package app.toque.companion.flows

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.Route
import app.toque.companion.ui.Tab
import app.toque.companion.ui.screens.EditPhase
import app.toque.companion.ui.screens.EditeurState
import app.toque.companion.ui.screens.EtapeState
import app.toque.companion.ui.screens.FicheState
import app.toque.companion.ui.screens.RecettesState
import app.toque.core.companion.SettingEdit
import app.toque.core.companion.SettingWire
import app.toque.core.companion.StepSettingsWire
import app.toque.core.json.Json
import app.toque.core.machine.Accessory
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.StepSettings
import app.toque.core.robot.RobotProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Le réglage du robot d'une étape choisi sur le téléphone (lot B), de bout en bout sur la JVM : la feuille
 * suit les bornes que le robot annonce, le brouillon garde ce qui est tapé, le robot juge et fait vérifier.
 */
class EditSettingsFlowTest {
    private val main = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }
    private val robots = ArrayList<SimRobot>()
    private val links = ArrayList<RobotLink>()
    private val schedule: Scheduler = { ms, r -> val f = main.schedule(r, ms, TimeUnit.MILLISECONDS); { f.cancel(false) } }
    private val dir: File = Files.createTempDirectory("brouillons").toFile()

    @After fun tearDown() {
        links.forEach { it.close() }
        robots.forEach { try { it.shutdown() } catch (_: Exception) {} }
        main.shutdownNow()
        dir.deleteRecursively()
    }

    private fun <T> onMain(block: () -> T): T = main.submit(Callable { block() }).get(10, TimeUnit.SECONDS)

    private fun eventually(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (onMain(cond)) return
            Thread.sleep(40)
        }
        throw AssertionError("délai dépassé : $what")
    }

    private fun paired(robot: SimRobot): RobotLink {
        val l = RobotLink(MemoryLinkStore(), post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") },
            ports = listOf(robot.port), waitSeconds = 3, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500).also { links += it }
        val done = CountDownLatch(1)
        var r: PairResult? = null
        l.pairWithCode(robot.hub.pairing.open().code) { r = it; done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue("$r", r is PairResult.Paired)
        onMain { l.follow("test") }
        eventually("connecté") { l.status.phase == LinkPhase.CONNECTED && l.latest != null }
        return l
    }

    private inner class Phone(link: RobotLink) {
        val nav = FakeNav()
        var list = RecettesState()
        var fiche: FicheState? = null
        var edit = EditeurState()
        var step: EtapeState? = null
        val catalog = CatalogFlow(link, nav, { list = it }, { fiche = it }, {}, schedule, pageSize = 20)
        val editor = EditFlow(link, nav, { edit = it }, { step = it }, DraftStore(dir), schedule,
            onSaved = { id, leaving -> catalog.recipeSaved(id, leaving) }, persistDelayMs = 20).also { catalog.editor = it }
    }

    private fun phone(link: RobotLink): Phone = onMain {
        Phone(link).also { p ->
            p.nav.select(Tab.RECIPES)
            p.catalog.ensureLoaded()
        }
    }

    private fun ready(p: Phone) = eventually("catalogue et droit d'écrire") { !p.list.loading && p.list.canWrite }

    private fun openVelouté(robot: SimRobot, p: Phone) {
        ready(p)
        onMain { p.catalog.editRecipe(robot.recipe.id) }
        eventually("chargée") { p.edit.phase == EditPhase.EDITING }
    }

    // ------------------------------------------------------------------ changer, retirer, garder

    @Test fun aStoredSettingIsChangedInTheSheetSavedAndToBeConfirmedOnTheRobot() {
        val robot = SimRobot().start().also { robots += it }
        val v = robot.recipe
        val p = phone(paired(robot))
        openVelouté(robot, p)
        onMain { p.editor.openStep(2) }
        val st = onMain { p.step!! }
        assertTrue(st.editable)
        assertEquals("3 min · 100 °C · vitesse 1", st.machine)
        assertEquals("Enregistré sur le robot", st.settingNote)
        // La feuille : les genres du Connect, la cuisson manuelle enregistrée, ses crans.
        onMain { p.editor.openSetting() }
        val sheet = onMain { p.step!!.sheet!! }
        assertEquals(listOf("", "manuel", "petrir", "vapeur", "rissoler", "turbo"), sheet.kinds.map { it.first })
        assertEquals("manuel", sheet.selected)
        assertEquals(listOf("seconds", "tempC", "speed"), sheet.rows.map { it.key })
        assertEquals("03:00", sheet.rows[0].value)
        assertFalse(sheet.confirm)
        assertEquals(0, sheet.reverse)
        assertEquals(0, sheet.whisk)
        // Vitesse au maximum en chauffe : la correction de l'écran du robot, mot pour mot.
        onMain { repeat(3) { p.editor.stepSetting("speed", 1) } }
        assertEquals("3", onMain { p.step!!.sheet!!.rows[2].value })
        assertEquals("En chauffe, vitesse 3 maximum", onMain { p.step!!.sheet!!.message })
        assertTrue(onMain { p.step!!.sheet!!.confirm })
        onMain { p.editor.applySetting() }
        assertNull(onMain { p.step!!.sheet })
        assertEquals("3 min · 100 °C · vitesse 3", onMain { p.step!!.machine })
        assertTrue(onMain { p.step!!.typed })
        assertEquals("Nouveau réglage : à confirmer une fois sur le robot avant le premier lancement", onMain { p.edit.steps[2].settingNote })
        // Gardé dans le brouillon, avec le réglage enregistré.
        eventually("brouillon écrit") { DraftStore(dir).load(v.id)?.steps?.get(2)?.edit != null }
        val d = DraftStore(dir).load(v.id)!!
        assertEquals(SettingEdit(StepMachine(StepProgram.MANUAL, 180, 3, 100)), d.steps[2].edit)
        assertEquals(StepMachine(StepProgram.MANUAL, 180, 1, 100), d.steps[2].stored)
        onMain { p.editor.closeStep(); p.editor.save() }
        eventually("enregistrée") { robot.written[v.id]?.steps?.get(2)?.machine?.speed == 3 }
        val saved = robot.written[v.id]!!
        assertTrue(saved.steps[2].needsReview)
        assertEquals(v.steps[0], saved.steps[0])
        assertEquals(v.steps[1], saved.steps[1])
    }

    @Test fun removingKeepingAndPuttingBackTheStoredSetting() {
        val robot = SimRobot().start().also { robots += it }
        val v = robot.recipe
        val p = phone(paired(robot))
        openVelouté(robot, p)
        onMain { p.editor.openStep(2); p.editor.openSetting(); p.editor.pickSetting(""); p.editor.applySetting() }
        assertNull(onMain { p.step!!.machine })
        assertEquals("Le réglage enregistré sera retiré à l'enregistrement", onMain { p.step!!.settingNote })
        // Reprendre le réglage enregistré : l'étape redevient « pas touchée ».
        onMain { p.editor.openSetting() }
        assertTrue(onMain { p.step!!.sheet!!.canRestore })
        onMain { p.editor.restoreSetting() }
        assertEquals("3 min · 100 °C · vitesse 1", onMain { p.step!!.machine })
        assertNull(onMain { p.editor.draft!!.steps[2].edit })
        // Changé puis remis à l'identique : pas touché non plus.
        onMain {
            p.editor.openSetting(); p.editor.stepSetting("speed", 1); p.editor.applySetting()
            p.editor.openSetting(); p.editor.stepSetting("speed", -1); p.editor.applySetting()
        }
        assertNull(onMain { p.editor.draft!!.steps[2].edit })
        assertFalse(Json.write(onMain { p.editor.draft!!.request() }.toMap()).contains("machine"))
        // Annuler ne change rien.
        onMain { p.editor.openSetting(); p.editor.pickSetting("turbo"); p.editor.closeSetting() }
        assertNull(onMain { p.editor.draft!!.steps[2].edit })
        // Retiré pour de bon.
        onMain { p.editor.openSetting(); p.editor.pickSetting(""); p.editor.applySetting(); p.editor.closeStep(); p.editor.save() }
        eventually("enregistrée") { robot.written[v.id] != null }
        assertNull(robot.written[v.id]!!.steps[2].machine)
        assertFalse(robot.written[v.id]!!.steps[2].needsReview)
    }

    @Test fun aNewRecipeAsksTheRobotWhatItOffersAndAProgramIsChosen() {
        val robot = SimRobot().start().also { robots += it }
        val p = phone(paired(robot))
        ready(p)
        onMain { p.catalog.writeRecipe() }
        onMain { p.editor.setTitle("Pain") }
        onMain { p.editor.addStep(); p.editor.setStepText("Pétrir la pâte.") }
        eventually("réglages connus") { p.step?.editable == true }
        onMain { p.editor.openSetting() }
        assertEquals("", onMain { p.step!!.sheet!!.selected })
        assertEquals("Aucun réglage", onMain { p.step!!.sheet!!.summary })
        onMain { p.editor.pickSetting("petrir") }
        val s = onMain { p.step!!.sheet!! }
        assertEquals(listOf("seconds"), s.rows.map { it.key })
        assertEquals("01:30", s.rows[0].value)
        assertTrue(s.hint.startsWith("Pâtes à pain"))
        // Pas et bornes que le robot annonce : 15 s, jusqu'à 2 min.
        onMain { repeat(5) { p.editor.stepSetting("seconds", 1) } }
        assertEquals("02:00", onMain { p.step!!.sheet!!.rows[0].value })
        assertFalse(onMain { p.step!!.sheet!!.rows[0].plusOk })
        onMain { p.editor.applySetting(); p.editor.closeStep() }
        // Une étape nouvelle vide mais avec un réglage reste.
        onMain { p.editor.addStep(); p.editor.openSetting(); p.editor.pickSetting("turbo"); p.editor.stepSetting("pulses", 1); p.editor.applySetting(); p.editor.closeStep() }
        assertEquals(2, onMain { p.edit.steps.size })
        onMain { p.editor.save() }
        eventually("créée") { robot.written.values.any { it.title == "Pain" } }
        val r = robot.written.values.first { it.title == "Pain" }
        assertEquals(listOf(StepMachine(StepProgram.KNEAD, 120, 4), StepMachine(StepProgram.TURBO, pulses = 4)), r.steps.map { it.machine })
        assertTrue(r.steps.all { it.needsReview })
    }

    // ------------------------------------------------------------------ refus et robot plus ancien

    @Test fun aRefusedSettingShowsTheRobotsReasonAtTheStepAndKeepsTheDraft() {
        val robot = SimRobot().start().also { robots += it }
        val v = robot.recipe
        val link = paired(robot)
        val first = phone(link)
        openVelouté(robot, first)
        // Un réglage que la feuille ne laisserait pas faire (brouillon venu d'ailleurs) : le robot juge.
        val bad = StepMachine(StepProgram.MANUAL, 180, 6, 100)
        onMain {
            first.editor.openStep(2); first.editor.openSetting(); first.editor.stepSetting("speed", 1); first.editor.applySetting()
            first.editor.closeStep()
            first.editor.close()
        }
        val withBad = DraftStore(dir).load(v.id)!!.let { d -> d.copy(steps = d.steps.mapIndexed { i, s -> if (i == 2) s.copy(edit = SettingEdit(bad)) else s }) }
        DraftStore(dir).save(withBad)
        // L'appli relancée : le brouillon est proposé, repris, enregistré.
        val p = phone(link)
        ready(p)
        onMain { p.catalog.editRecipe(v.id) }
        onMain { p.editor.resumeDraft() }
        onMain { p.editor.save() }
        eventually("étape refusée ouverte") { p.nav.current == Route.ETAPE && p.step?.refused != null }
        assertEquals("Étape 3 : En chauffe, vitesse 3 maximum", onMain { p.step!!.refused })
        assertTrue(onMain { p.step!!.settingRefused })
        assertEquals(3, onMain { p.step!!.number })
        assertTrue(onMain { p.edit.dirty })
        assertNull(robot.written[v.id])
        assertNotNull(DraftStore(dir).load(v.id)!!.steps[2].edit)
        // Corrigé dans la feuille : le refus s'en va, l'enregistrement passe.
        onMain { p.editor.openSetting() }
        assertEquals("6", onMain { p.step!!.sheet!!.rows[2].value })
        // Comme sur l'écran du robot : au-dessus du plafond en chauffe, « − » ramène la vitesse au plafond, avec son message.
        onMain { p.editor.stepSetting("speed", -1) }
        assertEquals("3", onMain { p.step!!.sheet!!.rows[2].value })
        assertEquals("En chauffe, vitesse 3 maximum", onMain { p.step!!.sheet!!.message })
        onMain { p.editor.applySetting() }
        assertNull(onMain { p.step!!.refused })
        assertFalse(onMain { p.step!!.settingRefused })
        onMain { p.editor.closeStep(); p.editor.save() }
        eventually("enregistrée") { robot.written[v.id]?.steps?.get(2)?.machine == StepMachine(StepProgram.MANUAL, 180, 3, 100) }
    }

    @Test fun aRobotThatDoesNotTakeSettingsOffersNone() {
        val robot = SimRobot().also { it.noSettings = true }.start().also { robots += it }
        val v = robot.recipe
        val p = phone(paired(robot))
        openVelouté(robot, p)
        onMain { p.editor.openStep(2) }
        assertFalse(onMain { p.step!!.editable })
        assertNull(onMain { p.step!!.settingNote })
        onMain { p.editor.openSetting() }
        assertNull(onMain { p.step!!.sheet })
        onMain { p.editor.setStepText("Faire revenir longtemps."); p.editor.closeStep(); p.editor.save() }
        eventually("enregistrée") { robot.written[v.id] != null }
        assertEquals(v.steps[2].machine, robot.written[v.id]!!.steps[2].machine)
        // Recette nouvelle : rien non plus.
        eventually("fermé") { p.nav.current != Route.EDITEUR }
        onMain { p.catalog.writeRecipe(); p.editor.addStep() }
        Thread.sleep(300)
        assertFalse(onMain { p.step!!.editable })
        assertNull(onMain { p.edit.steps[0].settingNote })
    }

    // ------------------------------------------------------------------ brouillon et feuille (purs)

    @Test fun aDraftOf039AndATypedDraftReadBack() {
        // Brouillon écrit par 0.3.9 : ni « stored » ni « edit » ; aucun réglage ne part, chaque réglage reste au robot.
        val old = mapOf("draftId" to "d1", "recipeId" to "perso-x", "version" to "v1", "title" to "Soupe", "servings" to 4, "minutes" to 0,
            "difficulty" to 1, "groups" to emptyList<Any>(),
            "steps" to listOf(mapOf("from" to 1, "text" to "Cuire.", "grams" to 0, "machine" to "20 min · 100 °C · vitesse 1", "review" to true)),
            "changedAt" to 5L)
        val d = Draft.fromMap(Json.parse(Json.write(old)) as Map<String, Any?>)!!
        assertEquals(DraftStep(1, "Cuire.", 0, "20 min · 100 °C · vitesse 1", true), d.steps[0])
        assertEquals("20 min · 100 °C · vitesse 1", d.steps[0].summary)
        assertFalse(Json.write(d.request().toMap()).contains("machine"))
        // Sa feuille : rien de choisi tant qu'on n'y touche pas ; « Utiliser » ne change rien.
        val st = StepSettingsWire.of(RobotProfiles.MC_CONNECT)
        val keep = SettingChoice(keep = true)
        val s = SettingChoice.state(1, st, keep, null, d.steps[0].summary, canRestore = false)
        assertEquals(SettingChoice.KEEP, s.selected)
        assertEquals("20 min · 100 °C · vitesse 1", s.summary)
        assertTrue(s.rows.isEmpty())
        assertEquals("manuel", SettingChoice.state(1, st, keep.pick(st, StepProgram.MANUAL), null, null, false).selected)

        // Brouillon de 0.4.0 : le réglage enregistré et celui tapé ici, relus tels quels.
        val typed = d.copy(steps = listOf(d.steps[0].copy(stored = StepMachine(StepProgram.MANUAL, 1200, 1, 100),
            edit = SettingEdit(StepMachine(StepProgram.STEAM, 1800))), DraftStep(null, "Servir.", edit = SettingEdit(null))))
        val back = Draft.fromMap(Json.parse(Json.write(typed.toMap())) as Map<String, Any?>)!!
        assertEquals(typed, back)
        assertEquals(listOf(SettingWire.toMap(StepMachine(StepProgram.STEAM, 1800)), null), back.request().steps.map { it.toMap()["machine"] })
        assertTrue(back.request().steps.all { it.toMap().containsKey("machine") })
        // Un réglage illisible (robot plus récent) : l'étape reste, sans réglage tapé.
        val odd = Json.parse(Json.write(typed.toMap()).replace("\"vapeur\"", "\"flamber\"")) as Map<String, Any?>
        assertNull(Draft.fromMap(odd)!!.steps[0].edit)
    }

    /** Un réglage venu d'une recette Lidl (pétrissage sans vitesse, Turbo compté en secondes) se retouche sur le téléphone. */
    @Test fun aLidlProgramTouchedOnThePhoneBecomesWhatTheRobotsEditorOffers() {
        val st = StepSettingsWire.of(RobotProfiles.MC_CONNECT)
        val lidl = StepMachine(StepProgram.KNEAD, seconds = 60)
        var k = SettingChoice.start(st, lidl)
        // Ouvert sans y toucher : rien à envoyer, rien à confirmer.
        assertTrue(k.unchanged(st, lidl))
        assertFalse(SettingChoice.state(3, st, k, lidl, null, false).confirm)
        k = k.step(st, "seconds", 1)
        assertEquals(StepMachine(StepProgram.KNEAD, 75, 4), k.machine)
        assertNull(StepSettings.refusal(k.machine!!, RobotProfiles.MC_CONNECT))
        assertFalse(k.unchanged(st, lidl))
        assertTrue(SettingChoice.state(3, st, k, lidl, null, false).confirm)
        // Revenu à la même durée : c'est le réglage enregistré.
        k = k.step(st, "seconds", -1)
        assertTrue(k.unchanged(st, lidl))
        assertFalse(SettingChoice.state(3, st, k, lidl, null, false).confirm)
        // Turbo Lidl de 20 secondes : un cran le ramène dans les bornes de l'éditeur du robot.
        val turbo = StepMachine(StepProgram.TURBO, seconds = 20, pulses = 20)
        var t = SettingChoice.start(st, turbo)
        val row = SettingChoice.state(3, st, t, turbo, null, false).rows.single()
        assertTrue(row.minusOk)
        assertFalse(row.plusOk)
        t = t.step(st, "pulses", -1)
        assertEquals(StepMachine(StepProgram.TURBO, pulses = 10), t.machine)
        assertNull(StepSettings.refusal(t.machine!!, RobotProfiles.MC_CONNECT))
        assertFalse(t.unchanged(st, turbo))
        // Un champ que le genre ne règle pas ne change rien.
        assertEquals(t, t.step(st, "speed", 1))
    }

    @Test fun theSheetFollowsTheRobotsRulesAndRanges() {
        val st = StepSettingsWire.of(RobotProfiles.MC_CONNECT)
        // Sans chauffe, vitesse 1 au moins ; en chauffe, la lame peut s'arrêter (Connect) ; sans mélange, pas de sens.
        var c = SettingChoice().pick(st, StepProgram.MANUAL)
        assertEquals(StepMachine(seconds = 60, speed = 2), c.machine)
        c = c.step(st, "speed", -1).step(st, "speed", -1)
        assertEquals(1, c.machine!!.speed)
        assertEquals("Sans chauffe, vitesse 1 minimum", c.message)
        c = c.step(st, "tempC", 1)
        assertEquals(37, c.machine!!.tempC)
        c = c.step(st, "speed", -1)
        assertEquals(0, c.machine!!.speed)
        assertEquals("Sans mélange : surveillez la cuisson", SettingChoice.state(1, st, c, null, null, false).hint)
        assertTrue(SettingChoice.state(1, st, c, null, null, false).reverseDimmed)
        assertEquals(c, c.reverse(true))
        // Le fouet plafonne la vitesse à 4, comme sur le robot.
        c = SettingChoice().pick(st, StepProgram.MANUAL).let { var x = it; repeat(6) { x = x.step(st, "speed", 1) }; x }
        assertEquals(8, c.machine!!.speed)
        c = c.whisk(true)
        assertEquals(StepMachine(seconds = 60, speed = 4, accessory = Accessory.WHISK), c.machine)
        assertEquals("Fouet : vitesse 4 maximum", c.message)
        // Au-dessus de la vitesse 6 : 15 min au plus.
        val fast = SettingChoice().pick(st, StepProgram.MANUAL).let { var x = it; repeat(5) { x = x.step(st, "speed", 1) }; x }
        assertEquals("max 15 min", SettingChoice.state(1, st, fast, null, null, false).rows[0].note)
        // Un programme : les bornes annoncées par le robot, jamais au-delà.
        var v = SettingChoice().pick(st, StepProgram.TOAST)
        repeat(30) { v = v.step(st, "seconds", 1) }
        assertEquals(840, v.machine!!.seconds)
        repeat(60) { v = v.step(st, "seconds", -1) }
        assertEquals(30, v.machine!!.seconds)
        var t = SettingChoice().pick(st, StepProgram.TURBO)
        repeat(20) { t = t.step(st, "pulses", 1) }
        assertEquals(10, t.machine!!.pulses)
        // Un genre que ce robot n'a pas (recette du Smart) : montré, sans crans ; un autre se choisit.
        val smart = SettingChoice.start(st, StepMachine(StepProgram.MIXER, 30))
        val s = SettingChoice.state(1, st, smart, StepMachine(StepProgram.MIXER, 30), null, false)
        assertEquals("mixer", s.selected)
        assertEquals("mixer", s.kinds.last().first)
        assertTrue(s.rows.isEmpty())
        assertFalse(s.confirm)
        assertEquals(smart, smart.pick(st, StepProgram.SMOOTHIE))
        assertEquals(StepProgram.STEAM, smart.pick(st, StepProgram.STEAM).machine!!.program)
        // Le Smart : ses programmes, sa plage de température.
        val sm = StepSettingsWire.of(RobotProfiles.MC_SMART)
        var sv = SettingChoice().pick(sm, StepProgram.SOUS_VIDE)
        repeat(40) { sv = sv.step(sm, "tempC", 1) }
        assertEquals(85, sv.machine!!.tempC)
        assertEquals(listOf("seconds", "tempC"), SettingChoice.state(1, sm, sv, null, null, false).rows.map { it.key })
        // Pas de chauffe sans mélange sur le Smart.
        var m = SettingChoice().pick(sm, StepProgram.MANUAL).step(sm, "tempC", 1)
        repeat(3) { m = m.step(sm, "speed", -1) }
        assertEquals(1, m.machine!!.speed)
    }
}
