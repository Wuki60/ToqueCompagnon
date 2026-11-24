package app.toque.companion.flows

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.Route
import app.toque.companion.ui.Tab
import app.toque.companion.ui.screens.EditBanner
import app.toque.companion.ui.screens.EditPhase
import app.toque.companion.ui.screens.EditeurState
import app.toque.companion.ui.screens.EtapeState
import app.toque.companion.ui.screens.FicheState
import app.toque.companion.ui.screens.RecettesState
import app.toque.core.companion.EditGroupWire
import app.toque.core.companion.EditStepWire
import app.toque.core.companion.EditableRecipe
import app.toque.core.companion.MachineStepWire
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
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
 * Écrire ses recettes sur le téléphone, de bout en bout sur la JVM : vrai hub sur base simulée, vraie
 * liaison, brouillons dans un vrai dossier, flux appelés comme les écrans les appellent.
 */
class EditFlowTest {
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

    /** L'onglet Recettes et l'éditeur, branchés comme dans l'activité. */
    private inner class Phone(link: RobotLink) {
        val nav = FakeNav()
        var list = RecettesState()
        var fiche: FicheState? = null
        var edit = EditeurState()
        var step: EtapeState? = null
        val notices = ArrayList<String>()
        val catalog = CatalogFlow(link, nav, { list = it }, { fiche = it }, {}, schedule, notice = { notices += it }, pageSize = 20)
        val editor = EditFlow(link, nav, { edit = it }, { step = it }, DraftStore(dir), schedule, notice = { notices += it },
            onSaved = { id, leaving -> catalog.recipeSaved(id, leaving) }, persistDelayMs = 20).also { catalog.editor = it }
    }

    private fun phone(link: RobotLink): Phone = onMain {
        Phone(link).also { p ->
            p.nav.select(Tab.RECIPES)
            p.catalog.ensureLoaded()
        }
    }

    private fun ready(p: Phone) = eventually("catalogue et droit d'écrire") { !p.list.loading && p.list.canWrite }

    @Test fun aNewRecipeIsWrittenSavedOnTheRobotAndItsSheetOpens() {
        val robot = SimRobot().start().also { robots += it }
        val p = phone(paired(robot))
        ready(p)
        onMain { p.catalog.filter(RecipeChip.USER) }
        eventually("Mes recettes") { p.list.filter == RecipeChip.USER && !p.list.loading && p.list.canWrite }

        onMain { p.catalog.writeRecipe() }
        assertEquals(Route.EDITEUR, onMain { p.nav.current })
        assertEquals(EditPhase.EDITING, onMain { p.edit.phase })
        assertEquals("Nouvelle recette", onMain { p.edit.heading })
        onMain {
            p.editor.setTitle("Pâte à crêpes")
            p.editor.setServings(6)
            p.editor.setMinutes(15)
            p.editor.setDifficulty(2)
            p.editor.addIngredient()
            p.editor.setIngredient(0, "250 g de farine")
            p.editor.addIngredient(0)
            p.editor.setIngredient(1, "4 œufs")
        }
        assertEquals(listOf("250 g de farine", "4 œufs"), onMain { p.edit.lines.map { it.text } })
        assertEquals(1, onMain { p.edit.focusLine })
        // Une étape : l'écran Étape s'ouvre ; une étape laissée vide s'en va au retour.
        onMain { p.editor.addStep() }
        assertEquals(Route.ETAPE, onMain { p.nav.current })
        assertTrue(onMain { p.step!!.isNew })
        onMain { p.editor.setStepText("Tout mixer.") }
        onMain { p.editor.closeStep() }
        onMain { p.editor.addStep(); p.editor.closeStep() }
        onMain { p.editor.addStep(); p.editor.setStepText("Verser le lait."); p.editor.setStepGrams(500); p.editor.closeStep() }
        assertEquals(Route.EDITEUR, onMain { p.nav.current })
        assertEquals(listOf("Tout mixer.", "Verser le lait."), onMain { p.edit.steps.map { it.text } })
        assertTrue(onMain { p.edit.dirty })
        assertEquals("Brouillon gardé sur le téléphone", onMain { p.edit.note })
        eventually("brouillon écrit") { DraftStore(dir).latestNew()?.title == "Pâte à crêpes" }

        onMain { p.editor.save() }
        eventually("enregistrée") { robot.written.values.any { it.title == "Pâte à crêpes" } }
        val r = robot.written.values.first { it.title == "Pâte à crêpes" }
        assertEquals(6, r.servings)
        assertEquals(2, r.difficulty)
        assertEquals(listOf(null, 500), r.steps.map { it.weigh?.grams })
        assertTrue(r.steps.all { it.machine == null })
        // Brouillon retiré, éditeur fermé, fiche de la recette enregistrée.
        eventually("fiche") { p.nav.current == Route.FICHE && p.fiche?.id == r.id && p.fiche?.loading == false }
        assertTrue(DraftStore(dir).all().isEmpty())
        assertTrue(onMain { p.notices.contains("Recette enregistrée sur le robot") })
        assertTrue(onMain { p.fiche!!.canWrite && p.fiche!!.personal })
    }

    @Test fun storedMachineStepsStayWhenTextIsEditedMovedAndRemovedOnThePhone() {
        val robot = SimRobot().start().also { robots += it }
        val v = robot.recipe
        val p = phone(paired(robot))
        ready(p)
        onMain { p.catalog.openRecipe(v.id) }
        eventually("fiche") { p.fiche?.loading == false }
        onMain { p.catalog.editRecipe(v.id) }
        eventually("chargée") { p.edit.phase == EditPhase.EDITING }
        assertEquals("Modifier la recette", onMain { p.edit.heading })
        val machine = onMain { p.edit.steps[2].machine }
        assertEquals("3 min · 100 °C · vitesse 1", machine)
        // Le réglage monte ; « Couper » s'en va ; le texte du réglage change.
        onMain {
            p.editor.moveStep(2, -1)
            p.editor.openStep(0)
            p.editor.removeStep()
            p.editor.openStep(0)
            p.editor.setStepText("Faire revenir l'oignon 3 minutes.")
            p.editor.closeStep()
        }
        assertEquals("3 min · 100 °C · vitesse 1", onMain { p.edit.steps[0].machine })
        assertEquals("3 min · 100 °C · vitesse 1", onMain { p.step!!.machine })
        onMain { p.editor.save() }
        eventually("enregistrée") { robot.written[v.id] != null }
        val saved = robot.written[v.id]!!
        assertEquals(listOf("Faire revenir l'oignon 3 minutes.", "Mettre le beurre dans le bol."), saved.steps.map { it.text })
        assertEquals(StepMachine(StepProgram.MANUAL, 180, 1, 100), saved.steps[0].machine)
        assertEquals(v.steps[1], saved.steps[1])
        eventually("retour à la fiche") { p.nav.current == Route.FICHE && p.fiche?.stepCount == 2 }
    }

    @Test fun theDraftSurvivesAndIsOfferedAgainThenResumedOrDiscarded() {
        val robot = SimRobot().start().also { robots += it }
        val link = paired(robot)
        val v = robot.recipe
        val p = phone(link)
        ready(p)
        onMain { p.catalog.editRecipe(v.id) }
        eventually("chargée") { p.edit.phase == EditPhase.EDITING }
        onMain { p.editor.setTitle("Velouté du dimanche") }
        // Fermeture de l'appli : le brouillon est écrit tout de suite.
        onMain { p.editor.close() }
        assertEquals("Velouté du dimanche", DraftStore(dir).load(v.id)!!.title)

        // Appli relancée : le brouillon est proposé.
        val q = phone(link)
        ready(q)
        onMain { q.catalog.editRecipe(v.id) }
        assertEquals(EditPhase.OFFER, onMain { q.edit.phase })
        assertTrue(onMain { q.edit.message!!.startsWith("Brouillon du ") })
        onMain { q.editor.resumeDraft() }
        assertEquals("Velouté du dimanche", onMain { q.edit.title })
        assertTrue(onMain { q.edit.dirty })
        // Quitter garde le brouillon ; « Recommencer » à la réouverture le retire et relit le robot.
        onMain { q.editor.leave() }
        assertEquals(Route.RECETTES, onMain { q.nav.current })
        onMain { q.catalog.editRecipe(v.id) }
        onMain { q.editor.discardDraft() }
        eventually("version du robot") { q.edit.phase == EditPhase.EDITING && q.edit.title == v.title }
        assertNull(DraftStore(dir).load(v.id))
        assertFalse(onMain { q.edit.dirty })
    }

    @Test fun eachRefusalOfTheRobotIsShownWhereItBelongs() {
        val robot = SimRobot().start().also { robots += it }
        val v = robot.recipe
        val p = phone(paired(robot))
        ready(p)
        onMain { p.catalog.editRecipe(v.id) }
        eventually("chargée") { p.edit.phase == EditPhase.EDITING }

        // Un champ : le message du robot sous le champ.
        onMain { p.editor.setTitle("  ") }
        onMain { p.editor.save() }
        eventually("refus du titre") { p.edit.problemField == "title" }
        assertEquals("Donnez un titre à la recette", onMain { p.edit.problemText })
        assertEquals(EditBanner.Kind.REFUSED, onMain { p.edit.banner!!.kind })
        onMain { p.editor.setTitle("Velouté") }
        assertNull(onMain { p.edit.problemField })

        // Une étape : elle s'ouvre, avec le message du robot.
        onMain { p.editor.openStep(1); p.editor.setStepGrams(9000); p.editor.closeStep() }
        onMain { p.editor.save() }
        eventually("étape refusée ouverte") { p.nav.current == Route.ETAPE && p.step?.refused != null }
        assertEquals("Étape 2 : Entrez un poids entre 1 et 5000 g", onMain { p.step!!.refused })
        assertEquals(2, onMain { p.step!!.number })
        assertEquals("Étape 2 : Entrez un poids entre 1 et 5000 g", onMain { p.edit.steps[1].refused })
        assertEquals("Corrigez l'étape 2, puis enregistrez de nouveau.", onMain { p.edit.banner!!.text })
        onMain { p.editor.setStepGrams(20); p.editor.closeStep() }
        assertNull(onMain { p.edit.steps[1].refused })

        // Ouverte dans l'éditeur du robot : son message.
        robot.editorOpen = v.id
        onMain { p.editor.save() }
        eventually("refus de l'éditeur") { p.edit.banner?.text == "Recette ouverte dans l'éditeur du robot" }
        robot.editorOpen = null

        // Version dépassée : reprendre celle du robot, ou garder son brouillon et l'enregistrer par-dessus.
        robot.written[v.id] = v.copy(title = "Velouté du robot")
        onMain { p.editor.setTitle("Velouté du téléphone") }
        onMain { p.editor.save() }
        eventually("version dépassée") { p.edit.banner?.kind == EditBanner.Kind.STALE }
        onMain { p.editor.keepDraft() }
        eventually("brouillon reposé") { p.edit.banner == null && !p.edit.saving }
        onMain { p.editor.save() }
        eventually("enregistrée par-dessus") { robot.written[v.id]?.title == "Velouté du téléphone" }
        assertEquals(StepMachine(StepProgram.MANUAL, 180, 1, 100), robot.written[v.id]!!.steps[2].machine)

        // Et « Reprendre la version du robot ».
        eventually("fermé") { p.nav.current == Route.FICHE || p.nav.current == Route.RECETTES }
        onMain { p.catalog.editRecipe(v.id) }
        eventually("rechargée") { p.edit.phase == EditPhase.EDITING }
        robot.written[v.id] = v.copy(title = "Velouté changé sur le robot")
        onMain { p.editor.setTitle("Autre") }
        onMain { p.editor.save() }
        eventually("version dépassée") { p.edit.banner?.kind == EditBanner.Kind.STALE }
        onMain { p.editor.reloadFromRobot() }
        eventually("version du robot") { p.edit.title == "Velouté changé sur le robot" && !p.edit.dirty }
        assertNull(DraftStore(dir).load(v.id))
    }

    @Test fun whenTheLinkIsDownTheDraftStaysAndThePhoneSaysSo() {
        val robot = SimRobot().start().also { robots += it }
        val v = robot.recipe
        val p = phone(paired(robot))
        ready(p)
        onMain { p.catalog.editRecipe(v.id) }
        eventually("chargée") { p.edit.phase == EditPhase.EDITING }
        onMain { p.editor.setTitle("Velouté hors ligne") }
        robot.shutdown()
        eventually("hors ligne") { p.edit.note == "Robot injoignable : le brouillon reste sur le téléphone" }
        onMain { p.editor.save() }
        assertEquals(EditBanner.Kind.OFFLINE, onMain { p.edit.banner!!.kind })
        assertEquals(Route.EDITEUR, onMain { p.nav.current })
        eventually("brouillon écrit") { DraftStore(dir).load(v.id)?.title == "Velouté hors ligne" }
    }

    @Test fun myVersionCreatesTheCopyOnTheRobotThenOpensIt() {
        val robot = SimRobot().start().also { robots += it }
        val p = phone(paired(robot))
        ready(p)
        onMain { p.catalog.openRecipe("essai-2") }
        eventually("fiche") { p.fiche?.loading == false }
        assertTrue(onMain { p.fiche!!.canWrite && !p.fiche!!.personal })
        onMain { p.catalog.myVersion("essai-2") }
        eventually("copie ouverte") { p.edit.phase == EditPhase.EDITING }
        assertEquals("Ma version", onMain { p.edit.heading })
        val copy = robot.written.values.single()
        assertTrue(copy.id.startsWith("perso-"))
        assertEquals("Mélanger.", copy.steps[1].text)
        assertNotNull(copy.steps[1].machine)
        assertEquals("30 s · vitesse 3", onMain { p.edit.steps[1].machine })
        onMain { p.editor.setTitle("Ma soupe") }
        onMain { p.editor.save() }
        eventually("copie modifiée") { robot.written[copy.id]?.title == "Ma soupe" }
        eventually("fiche de la copie") { p.nav.current == Route.FICHE && p.fiche?.id == copy.id && p.fiche?.loading == false }
    }

    @Test fun anOlderRobotShowsNothingNew() {
        val robot = SimRobot().also { it.older = true }.start().also { robots += it }
        val p = phone(paired(robot))
        eventually("catalogue") { !p.list.loading && p.list.items.isNotEmpty() }
        assertFalse(onMain { p.list.canWrite })
        onMain { p.catalog.writeRecipe() }
        assertEquals(Route.RECETTES, onMain { p.nav.current })
        onMain { p.catalog.openRecipe("essai-2") }
        eventually("fiche") { p.fiche?.loading == false }
        assertFalse(onMain { p.fiche!!.canWrite })
    }

    @Test fun keepingADraftOnANewerVersionFindsItsMachineStepsByTheirSetting() {
        val e = EditableRecipe("perso-x", "v2", "Soupe", 4, 30, 1,
            listOf(EditGroupWire("", listOf("sel"))),
            listOf(EditStepWire(0, "Nouvelle étape du robot."), EditStepWire(1, "Cuire.", 0,
                MachineStepWire("manuel", 1200, 1, 100, summary = "20 min · 100 °C · vitesse 1"), true)))
        val d = Draft("d", "perso-x", "v1", "Soupe", groups = listOf(DraftGroup("Disparu", listOf("poivre"))),
            steps = listOf(DraftStep(0, "Cuire longtemps.", 0, "20 min · 100 °C · vitesse 1", false), DraftStep(1, "Couper."),
                DraftStep(2, "Mixer.", 0, "1 min · vitesse 8")))
        val r = Draft.rebase(d, e)
        assertEquals("v2", r.version)
        assertEquals(DraftStep(1, "Cuire longtemps.", 0, "20 min · 100 °C · vitesse 1", true, StepMachine(StepProgram.MANUAL, 1200, 1, 100)), r.steps[0])
        assertEquals(DraftStep(null, "Couper."), r.steps[1])
        // Plus de réglage pareil sur le robot : l'étape garde son texte, sans réglage.
        assertEquals(DraftStep(null, "Mixer.", 0, null, false), r.steps[2])
        assertEquals("", r.groups[0].title)
        // Jamais de réglage dans la demande : seulement l'étape d'origine.
        assertEquals(listOf(1, null, null), r.request().steps.map { it.from })
        assertTrue(r.request().toMap().toString().contains("version=v2"))
        assertFalse(r.request().toMap().toString().contains("machine"))
    }
}
