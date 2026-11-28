package app.toque.companion.flows

import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.Route
import app.toque.companion.ui.Tab
import app.toque.companion.ui.screens.FicheState
import app.toque.companion.ui.screens.MonReglageState
import app.toque.core.companion.ApiError
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.CookSession
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import app.toque.core.json.Json
import app.toque.core.recipes.OwnStepSettings
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.SettingAuthor
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.StepSettings
import app.toque.core.recipes.corrected
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * « Mon réglage » d'une étape d'une recette Mealie depuis le téléphone, de bout en bout sur la JVM : un vrai hub
 * sur base simulée et la vraie liaison. La fiche marque les étapes ; le réglage part avec l'empreinte de l'étape
 * lue, le robot le garde comme venu du téléphone (à vérifier sur place) ; une étape changée sur le robot est
 * refusée et relue ; un refus montre la raison du robot ; un robot plus ancien ne change rien à la fiche.
 */
class OwnStepFlowTest {
    private val main = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }
    private val robots = ArrayList<SimRobot>()
    private val links = ArrayList<RobotLink>()
    private val servers = ArrayList<HttpServer>()
    private val schedule: Scheduler = { ms, r -> val f = main.schedule(r, ms, TimeUnit.MILLISECONDS); { f.cancel(false) } }

    /** Recette inventée, telle que la synchro Mealie l'écrit (réglages lus dans le texte, à vérifier). */
    private val tarte = Recipe(id = "mealie-5e6f7a8b", title = "Tarte d'essai", category = "Desserts", servings = 6,
        source = RecipeSource.MEALIE,
        steps = listOf(
            Step("Mettre la farine et le beurre dans le bol."),
            Step("Pétrir la pâte 2 min.", StepMachine(StepProgram.MANUAL, 120, 4), needsReview = true),
            Step("Cuire la compote 10 min à 90 °C.", StepMachine(StepProgram.MANUAL, 600, 1, 90), needsReview = true),
        ))
    private val knead = StepSettings.default(StepProgram.KNEAD)

    @After fun tearDown() {
        links.forEach { it.close() }
        servers.forEach { it.stop() }
        robots.forEach { it.shutdown() }
        main.shutdownNow()
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

    private fun robot() = SimRobot().start().also { r ->
        robots += r
        r.mealie[tarte.id] = tarte
        r.index = SearchIndex(r.recipes + tarte)
    }

    private fun paired(robot: SimRobot, port: Int = robot.port): RobotLink {
        val l = RobotLink(MemoryLinkStore(), post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") },
            ports = listOf(port), waitSeconds = 3, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500).also { links += it }
        val done = CountDownLatch(1)
        var r: PairResult? = null
        l.pairWithCode(robot.hub.pairing.open().code) { r = it; done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue("$r", r is PairResult.Paired)
        return l
    }

    /**
     * Relais devant [robot]. [older] : un robot d'avant ces routes (ni `can.ownSettings`, ni `ownSettings`, `key`,
     * `own` sur une fiche ; 404 sur les routes). [refuse] : le robot refuse tout réglage avec ce message (422).
     */
    private fun relay(robot: SimRobot, older: Boolean = false, refuse: String? = null): Int {
        val api = CompanionApi(robot.hub)
        val server = HttpServer(object : HttpHandler {
            override fun handle(request: HttpRequest): HttpResponse {
                val steps = request.path.contains("/steps")
                if (older && steps) return ApiError.response(404, "not_found", "Not found")
                if (refuse != null && steps && request.method == "POST")
                    return ApiError.response(422, "step_machine", refuse, "steps", 2)
                val r = api.handle(request)
                if (!older || r.status != 200) return r
                @Suppress("UNCHECKED_CAST")
                val m = try {
                    Json.parse(String(r.body, Charsets.UTF_8)) as? MutableMap<String, Any?>
                } catch (_: Exception) {
                    null
                } ?: return r
                when {
                    request.path == "/api/state" -> {
                        @Suppress("UNCHECKED_CAST")
                        (m["can"] as? MutableMap<String, Any?>)?.remove("ownSettings")
                    }
                    request.path.startsWith("/api/recipes/") -> {
                        m.remove("ownSettings")
                        @Suppress("UNCHECKED_CAST")
                        (m["steps"] as? List<MutableMap<String, Any?>>)?.forEach { it.remove("key"); it.remove("own") }
                    }
                    else -> return r
                }
                return HttpResponse.json(200, m)
            }
        }, 0, InetAddress.getByName("127.0.0.1"))
        server.start()
        servers += server
        return server.boundPort
    }

    private class Phone(val catalog: CatalogFlow, val own: OwnStepFlow, val nav: FakeNav, val notices: MutableList<String>) {
        @Volatile var fiche: FicheState? = null
        @Volatile var step: MonReglageState? = null
    }

    private fun phone(link: RobotLink): Phone {
        val nav = FakeNav()
        val notices = java.util.Collections.synchronizedList(ArrayList<String>())
        lateinit var p: Phone
        onMain {
            lateinit var catalog: CatalogFlow
            catalog = CatalogFlow(link, nav, {}, { p.fiche = it }, {}, schedule, notice = { notices += it })
            val own = OwnStepFlow(link, nav, { p.step = it }, notice = { notices += it }, onChanged = { catalog.ownStepChanged(it) })
            catalog.ownSteps = own
            p = Phone(catalog, own, nav, notices)
            link.follow("test")
            nav.select(Tab.RECIPES)
        }
        return p
    }

    private fun Phone.openSheet(id: String = tarte.id) {
        onMain { catalog.openRecipe(id) }
        eventually("fiche chargée") { fiche?.loading == false && fiche?.id == id && fiche?.canOwn == true }
    }

    private fun Phone.openStep(index: Int) {
        onMain { catalog.openOwnStep(tarte.id, index) }
        eventually("étape lue") { step?.loading == false && step?.number == index + 1 && step?.text?.isNotEmpty() == true }
    }

    private fun own(robot: SimRobot, i: Int) = OwnStepSettings.owned(tarte, robot.corrections[tarte.id] ?: app.toque.core.recipes.RecipeAdjustments())[i]

    @Test fun aStepGetsTheOwnersSettingFromThePhoneWithoutACopy() {
        val robot = robot()
        val p = phone(paired(robot))
        p.openSheet()
        val f = onMain { p.fiche!! }
        assertTrue(f.ownSettings)
        assertTrue(f.stepsTappable)
        // Les marques de la page du robot : rien à soi, deux réglages lus dans le texte, à vérifier.
        assertEquals(listOf("", "", ""), f.steps.map { it.own })
        assertEquals(listOf(false, true, true), f.steps.map { it.review })

        p.openStep(1)
        assertEquals(Route.REGLAGE, onMain { p.nav.current })
        val s = onMain { p.step!! }
        assertEquals("Pétrir la pâte 2 min.", s.text)
        assertEquals(tarte.steps[1].machine!!.summary, s.machine)
        assertEquals("", s.own)
        assertTrue(s.review)

        // Pétrir choisi dans la feuille, « Utiliser ce réglage » : il part aussitôt.
        onMain { p.own.openSetting() }
        assertNotNull(onMain { p.step!!.sheet })
        onMain { p.own.pickSetting(StepProgram.KNEAD.key) }
        assertTrue("à confirmer sur le robot", onMain { p.step!!.sheet!!.confirm })
        onMain { p.own.applySetting() }
        eventually("réglage enregistré") { p.step?.own == "phone" && p.step?.sending == false }
        assertEquals(listOf("${tarte.id}:1:${knead.summary}"), robot.ownWrites.toList())
        assertEquals(SettingAuthor.PHONE, own(robot, 1)!!.by)
        assertEquals(knead.summary, onMain { p.step!!.machine })
        assertEquals(tarte.steps[1].machine!!.summary, onMain { p.step!!.recipeSetting })
        assertTrue(onMain { p.notices.contains("Réglage envoyé : à vérifier sur le robot") })
        // Sur le robot : à vérifier, sur sa propre carte.
        val cooked = tarte.corrected(robot.corrections[tarte.id]!!).steps[1]
        assertTrue(cooked.needsReview && cooked.reviewAlone)
        // La fiche se relit et marque l'étape.
        eventually("fiche relue") { p.fiche?.steps?.getOrNull(1)?.own == "phone" }
        assertEquals(knead.summary, onMain { p.fiche!!.steps[1].detail })
        assertTrue(onMain { p.fiche!!.steps[1].review })

        // Le même réglage de nouveau : rien ne part.
        onMain { p.own.openSetting(); p.own.applySetting() }
        Thread.sleep(200)
        assertEquals(1, robot.ownWrites.size)

        // « Revenir au réglage de la recette ».
        onMain { p.own.resetOwnStep() }
        eventually("réglage de la recette") { p.step?.own == "" && p.step?.sending == false }
        assertEquals("${tarte.id}:1:recette", robot.ownWrites.last())
        assertNull(own(robot, 1))
        eventually("fiche relue") { p.fiche?.steps?.getOrNull(1)?.own == "" }

        onMain { p.own.closeOwnStep() }
        assertEquals(Route.FICHE, onMain { p.nav.current })
        onMain { p.catalog.close(); p.own.close() }
    }

    @Test fun aStepChangedOnTheRobotIsRefusedAndReadAgain() {
        val robot = robot()
        val p = phone(paired(robot))
        p.openSheet()
        p.openStep(2)
        // Le texte de l'étape change sur le robot (synchro Mealie) pendant que l'écran est ouvert.
        val changed = tarte.copy(steps = tarte.steps.mapIndexed { i, s -> if (i == 2) s.copy(text = "Cuire la compote 12 min à 90 °C.") else s })
        robot.mealie[tarte.id] = changed
        onMain { p.own.openSetting(); p.own.pickSetting(StepProgram.STEAM.key); p.own.applySetting() }
        eventually("étape relue") { p.step?.text == "Cuire la compote 12 min à 90 °C." && p.step?.sending == false }
        // Rien d'enregistré ; le message le dit, la fiche se relit.
        assertTrue(robot.ownWrites.isEmpty())
        assertEquals("La recette a changé sur le robot : relisez l'étape, puis choisissez de nouveau son réglage.", onMain { p.step!!.refused })
        eventually("fiche relue") { p.fiche?.steps?.getOrNull(2)?.text == "Cuire la compote 12 min à 90 °C." }
        // Sur l'étape relue, le réglage part.
        onMain { p.own.openSetting(); p.own.pickSetting(StepProgram.STEAM.key); p.own.applySetting() }
        eventually("enregistré") { p.step?.own == "phone" }
        assertEquals(1, robot.ownWrites.size)
        assertNull(onMain { p.step!!.refused })

        // Changée entre la fiche et l'ouverture de l'étape : l'écran le dit et montre le texte du robot.
        robot.mealie[tarte.id] = tarte
        onMain { p.catalog.ownStepChanged(tarte.id) }
        eventually("fiche relue") { p.fiche?.steps?.getOrNull(2)?.text == tarte.steps[2].text && p.fiche?.loading == false }
        robot.mealie[tarte.id] = changed
        onMain { p.catalog.openOwnStep(tarte.id, 2) }
        eventually("étape lue") { p.step?.loading == false && p.step?.text == "Cuire la compote 12 min à 90 °C." }
        assertEquals("La recette a changé sur le robot : relisez l'étape, puis choisissez de nouveau son réglage.", onMain { p.step!!.refused })
        onMain { p.catalog.close(); p.own.close() }
    }

    @Test fun aRefusalShowsTheRobotsReasonAndNothingWhileCooking() {
        val robot = robot()
        val p = phone(paired(robot, relay(robot, refuse = "Étape 3 : En chauffe, vitesse 3 maximum")))
        p.openSheet()
        p.openStep(2)
        onMain { p.own.openSetting(); p.own.pickSetting(StepProgram.KNEAD.key); p.own.applySetting() }
        eventually("refus") { p.step?.refused != null && p.step?.sending == false }
        val s = onMain { p.step!! }
        assertEquals("Étape 3 : En chauffe, vitesse 3 maximum", s.refused)
        assertTrue(s.settingRefused)
        assertEquals("", s.own)
        assertTrue(robot.ownWrites.isEmpty())
        onMain { p.catalog.close(); p.own.close() }

        // Recette en cours sur le robot : son refus, rien d'écrit.
        val robot2 = robot()
        val p2 = phone(paired(robot2))
        p2.openSheet()
        p2.openStep(1)
        robot2.hub.updateSession(CookSession.of(tarte, 0))
        onMain { p2.own.openSetting(); p2.own.pickSetting(StepProgram.KNEAD.key); p2.own.applySetting() }
        eventually("refus") { p2.step?.refused != null && p2.step?.sending == false }
        assertEquals("Recette en cours sur le robot", onMain { p2.step!!.refused })
        assertFalse(onMain { p2.step!!.settingRefused })
        assertTrue(robot2.ownWrites.isEmpty())
        onMain { p2.catalog.close(); p2.own.close() }
    }

    @Test fun anOlderRobotLeavesTheSheetAsItWas() {
        val robot = robot()
        val p = phone(paired(robot, relay(robot, older = true)))
        onMain { p.catalog.openRecipe(tarte.id) }
        eventually("fiche chargée") { p.fiche?.loading == false && p.fiche?.id == tarte.id }
        Thread.sleep(300)
        val f = onMain { p.fiche!! }
        assertFalse(f.ownSettings)
        assertFalse(f.canOwn)
        assertFalse(f.stepsTappable)
        assertTrue(f.steps.all { it.own.isEmpty() })
        // Même appelé, rien ne s'ouvre et rien ne part.
        onMain { p.catalog.openOwnStep(tarte.id, 1) }
        Thread.sleep(200)
        assertEquals(Route.FICHE, onMain { p.nav.current })
        assertNull(onMain { p.step })
        assertTrue(robot.ownWrites.isEmpty())
        onMain { p.catalog.close(); p.own.close() }
    }

    @Test fun aLidlRecipeHasNoOwnSettings() {
        val robot = robot()
        val p = phone(paired(robot))
        onMain { p.catalog.openRecipe("essai-3") }
        eventually("fiche chargée") { p.fiche?.loading == false && p.fiche?.id == "essai-3" && p.fiche?.canOwn == true }
        assertFalse(onMain { p.fiche!!.ownSettings })
        assertFalse(onMain { p.fiche!!.stepsTappable })
        onMain { p.catalog.openOwnStep("essai-3", 1) }
        Thread.sleep(200)
        assertNull(onMain { p.step })
        onMain { p.catalog.close(); p.own.close() }
    }
}
