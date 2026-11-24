package app.toque.companion

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridView
import android.widget.TextView
import app.toque.companion.ui.JournalActions
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.PairingActions
import app.toque.companion.ui.RecipeActions
import app.toque.companion.ui.RobotActions
import app.toque.companion.ui.Route
import app.toque.companion.ui.Samples
import app.toque.companion.ui.Screens
import app.toque.companion.ui.Shell
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.screens.AlertKind
import app.toque.companion.ui.screens.JournalFilter
import app.toque.companion.ui.screens.NowBar
import app.toque.companion.ui.screens.RecipeCard
import app.toque.core.recipes.RecipeChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Piles d'écrans, onglets, actions et mises à jour sur place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-port-xhdpi")
class NavigationTest {
    private lateinit var shell: Shell
    private lateinit var screens: Screens
    private val calls = ArrayList<String>()

    @Before fun setUp() {
        val c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Companion)
        Ui.init(c)
        // L'Application installe la langue du téléphone (anglais sous Robolectric) : ces tests lisent le français.
        CompanionLanguages.install(c, "fr")
        shell = Shell(c)
        screens = Screens(c, object : LiveActions {
            override fun pause() { calls += "pause" }
            override fun stop() { calls += "stop" }
            override fun addMinute() { calls += "+1" }
            override fun adjustSpeed(delta: Int) { calls += "speed $delta" }
            override fun withdrawSentRecipe() { calls += "withdraw" }
            override fun clearEnded() { calls += "ok" }
            override fun setReverse(reverse: Boolean) { calls += "reverse $reverse" }
            override fun setAccessory(whisk: Boolean) { calls += "whisk $whisk" }
        }, shell, recipes = object : RecipeActions {
            override fun search(query: String) { calls += "search $query" }
            override fun filter(filter: RecipeChip) { calls += "filter ${filter.key}" }
            override fun loadMore() { calls += "more" }
            override fun openRecipe(id: String) { calls += "open $id" }
            override fun sendToRobot(id: String) { calls += "send $id" }
        }, journalActions = object : JournalActions {
            override fun filter(filter: JournalFilter) { calls += "journal $filter" }
        }, robotActions = object : RobotActions {
            override fun forgetPhone() { calls += "forget" }
            override fun setAlert(kind: AlertKind, on: Boolean) { calls += "alert $kind $on" }
        }, pairing = object : PairingActions {
            override fun qrRead(text: String) { calls += "qr $text" }
            override fun codeSubmitted(code: String, address: String?) { calls += "code $code $address" }
            override fun cameraPermissionWanted() { calls += "camera" }
        })
        shell.screens = screens
        Samples.renderAll(screens)
        shell.refresh()
    }

    @Test fun startsOnTheRestingRobot() {
        assertEquals(Route.REPOS, shell.current)
    }

    @Test fun stepsOpenOverTheLiveScreenAndBackCloses() {
        shell.showLive(Route.MAIN)
        assertEquals(Route.MAIN, shell.current)
        click(screens.main.view, "Les étapes")
        assertEquals(Route.ETAPES, shell.current)
        // Le robot passe en pause pendant qu'on regarde les étapes : elles restent affichées.
        shell.showLive(Route.PAUSE)
        assertEquals(Route.ETAPES, shell.current)
        assertTrue(shell.back())
        assertEquals(Route.PAUSE, shell.current)
        assertFalse(shell.back())
    }

    @Test fun tabsKeepTheirStackAndBackReturnsToLive() {
        shell.select(Tab.LOG)
        assertEquals(Route.JOURNAL, shell.current)
        assertTrue(shell.back())
        assertEquals(Route.REPOS, shell.current)
        shell.open(Route.ETAPES)
        shell.select(Tab.RECIPES)
        shell.select(Tab.LIVE)
        assertEquals(Route.ETAPES, shell.current)
        // Toucher l'onglet affiché revient à sa racine.
        shell.select(Tab.LIVE)
        assertEquals(Route.REPOS, shell.current)
    }

    @Test fun buttonsCallTheActions() {
        val v = screens.main.view
        click(v, "+1 min")
        clickDescribed(v, "Mettre en pause")
        clickDescribed(v, "Arrêter")
        clickDescribed(screens.manuel.view, "Monter la vitesse")
        assertEquals(listOf("+1", "pause", "stop", "speed 1"), calls)
    }

    /** Fin d'un programme : racine de « En direct » (pas de retour), OK appelle l'action, grisé pendant l'attente. */
    @Test fun theEndScreenIsALiveRootAndOkClears() {
        shell.showLive(Route.FIN)
        assertEquals(Route.FIN, shell.current)
        assertFalse(shell.back())
        tap(screens.fin.view, "OK")
        assertEquals(listOf("ok"), calls)
        screens.fin.render(Samples.fin().copy(canOk = false))
        tap(screens.fin.view, "OK")
        assertEquals(listOf("ok"), calls)
        shell.showLive(Route.REPOS)
        assertEquals(Route.REPOS, shell.current)
    }

    /** Étapes : la barre d'une étape arrêtée a son OK à la place de la pause et de l'arrêt. */
    @Test fun theStepsEndedBarHasOk() {
        screens.etapes.render(Samples.etapes().copy(now = NowBar("Cuisson manuelle", 0, end = "Arrêté", canOk = true), root = true))
        shell.showLive(Route.ETAPES)
        tap(screens.etapes.view, "OK")
        assertEquals(listOf("ok"), calls)
    }

    /** Cuisson manuelle : segments du sens ; l'accessoire, verrouillé en cours, ne répond pas. */
    @Test fun manualSegments() {
        screens.manuel.render(Samples.manuel())
        tap(screens.manuel.view, "Inverse")
        tap(screens.manuel.view, "Fouet")
        assertEquals(listOf("reverse true"), calls)
        screens.manuel.render(Samples.manuel().copy(preparing = true))
        tap(screens.manuel.view, "Fouet")
        tap(screens.manuel.view, "Sens normal")
        assertEquals(listOf("reverse true", "whisk true", "reverse false"), calls)
    }

    @Test fun newStatesUpdateTheSameViews() {
        val before = all(screens.main.view)
        screens.main.render(Samples.main().copy(remainingMs = 61_000))
        assertTrue(texts(screens.main.view).contains("01:01"))
        val after = all(screens.main.view)
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (a, b) -> assertSame(a, b) }
    }

    @Test fun pairingIsFullScreenAndBackLeavesTheApp() {
        shell.showPairing(true)
        assertEquals(Route.APPAIRAGE, shell.current)
        val bar = (0 until shell.childCount).map { shell.getChildAt(it) }.last()
        assertEquals(View.GONE, bar.visibility)
        // Saisie du code : le retour revient au QR code, puis quitte l'appli.
        click(screens.appairage.view, "Saisir le code à 6 chiffres")
        type(screens.appairage.view, "482913")
        assertTrue(shell.back())
        assertFalse(shell.back())
        shell.showPairing(false)
        assertEquals(Route.REPOS, shell.current)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(listOf("code 482913 null"), calls)
    }

    @Test fun pairingAsksForTheAddressWhenTold() {
        screens.appairage.render(Samples.appairage().copy(askAddress = true))
        shell.showPairing(true)
        screens.appairage.showCodeEntry(true)
        type(screens.appairage.view, "482913")
        assertTrue(calls.isEmpty())
        all(screens.appairage.view).filterIsInstance<EditText>().first { it.contentDescription == "Adresse du robot" }.setText("192.168.0.12")
        click(screens.appairage.view, "Appairer")
        assertEquals(listOf("code 482913 192.168.0.12"), calls)
    }

    @Test fun recipesSearchFilterOpenAndLoadMoreOnce() {
        shell.select(Tab.RECIPES)
        val v = screens.recettes.view
        layout(shell)
        click(v, "Favoris")
        all(v).filterIsInstance<EditText>().first().setText("soupe")
        val grid = all(v).filterIsInstance<GridView>().first()
        assertTrue(grid.getChildAt(0).performClick())
        assertEquals(listOf("filter favorites", "search soupe", "open perso-1"), calls)
        // La grille montre toutes les cartes chargées et il en reste : une seule demande par page.
        calls.clear()
        screens.recettes.render(Samples.recettes().copy(loading = true))
        layout(shell)
        assertEquals(emptyList<String>(), calls)
        screens.recettes.render(Samples.recettes())
        layout(shell)
        assertEquals(listOf("more"), calls)
        screens.recettes.render(Samples.recettes().copy(loading = true))
        screens.recettes.render(Samples.recettes())
        layout(shell)
        assertEquals(listOf("more"), calls)
        screens.recettes.render(Samples.recettes().let { it.copy(items = it.items + RecipeCard("x", "Taboulé", 15, "Salades")) })
        layout(shell)
        assertEquals(listOf("more", "more"), calls)
        // Plus rien à charger : aucune demande.
        screens.recettes.render(Samples.recettes().let { it.copy(items = it.items + RecipeCard("y", "Houmous", 10, "Entrées"), hasMore = false) })
        layout(shell)
        assertEquals(listOf("more", "more"), calls)
    }

    @Test fun recipeSendWithdrawAndLive() {
        shell.open(Route.FICHE)
        click(screens.fiche.view, "Envoyer au robot")
        shell.open(Route.ENVOYEE)
        click(screens.envoyee.view, "Retirer du robot")
        click(screens.envoyee.view, "Voir En direct")
        assertEquals(Route.REPOS, shell.current)
        assertEquals(listOf("send perso-1", "withdraw"), calls)
    }

    @Test fun robotForgetAsksFirstAndSwitchesCallBack() {
        shell.select(Tab.ROBOT)
        val v = screens.robot.view
        click(v, "Oublier")
        assertTrue(calls.isEmpty())
        // La feuille de confirmation est ouverte : le retour la ferme.
        assertTrue(shell.back())
        assertEquals(Route.ROBOT, shell.current)
        click(v, "Oublier")
        val confirm = all(v).filterIsInstance<TextView>().last { it.text.toString() == "Oublier" }
        var b: View = confirm
        while (!b.isClickable) b = b.parent as View
        b.performClick()
        clickDescribed(v, "Alerte quand le robot est hors ligne")
        assertEquals(listOf("forget", "alert OFFLINE true"), calls)
    }

    @Test fun journalReusesItsCards() {
        shell.select(Tab.LOG)
        val v = screens.journal.view
        val before = all(v).size
        screens.journal.render(Samples.journal().copy(days = Samples.journal().days.reversed()))
        assertEquals(before, all(v).size)
        click(v, "Manuel")
        assertEquals(listOf("journal MANUAL"), calls)
    }

    private fun layout(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 780, 1688)
    }

    private fun type(root: View, digits: String) {
        all(root).filterIsInstance<EditText>().first { it.contentDescription == "Code à 6 chiffres" }.setText(digits)
    }

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().map { it.text.toString() }

    private fun click(root: View, text: String) {
        val t = all(root).filterIsInstance<TextView>().first { it.text.toString() == text }
        var v: View = t
        while (!v.isClickable) v = v.parent as View
        assertTrue(v.performClick())
    }

    /** Appui du doigt : sans effet sur une vue désactivée (performClick, lui, appellerait l'action). */
    private fun tap(root: View, text: String) {
        val t = all(root).filterIsInstance<TextView>().first { it.text.toString() == text }
        var v: View = t
        while (!v.isClickable) v = v.parent as View
        if (v.isEnabled) assertTrue(v.performClick())
    }

    private fun clickDescribed(root: View, description: String) {
        assertTrue(all(root).first { it.contentDescription == description }.performClick())
    }
}
