package app.toque.companion

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import app.toque.companion.link.FileLinkStore
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.PairedRobot
import app.toque.companion.link.SimRobot
import app.toque.companion.flows.AppearanceFlow
import app.toque.companion.ui.ChoiceSheet
import app.toque.companion.ui.Looks
import app.toque.companion.ui.Palette
import app.toque.companion.ui.Route
import app.toque.companion.ui.Swatch
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.screens.EditPhase
import app.toque.core.companion.RobotInfo
import app.toque.core.i18n.tr
import app.toque.core.look.ColourWheel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Un téléphone appairé à un robot simulé ; le téléphone a sa propre apparence (ciel, claire) dans ses préférences. */
class SimLookApp : CompanionApp() {
    override fun onCreate() {
        val r = SimRobot().start()
        robot = r
        // Au départ, la même apparence que le téléphone : rien à demander.
        r.hub.updateRobot(RobotInfo(NAME, "0.8.1", "fr", accent = "#4FC3F7", theme = "light"))
        val p = r.hub.pairing.pairWithCode(r.hub.pairing.open().code)
        FileLinkStore(File(noBackupFilesDir, "robot.json")).save(PairedRobot("127.0.0.1", r.port, p.token!!, p.phone!!.id,
            r.hub.pairing.robotId, NAME, 1L, "code"))
        getSharedPreferences("apparence", MODE_PRIVATE).edit().putString("accent", "#4FC3F7").putString("theme", "light").commit()
        super.onCreate()
        // Avant toute réponse du robot et tout écran : l'apparence du téléphone est déjà en place.
        accentAtStart = Ui.ACCENT
        lightAtStart = Ui.light
    }

    companion object {
        const val NAME = "Monsieur Cuisine Connect"
        @Volatile var robot: SimRobot? = null
        @Volatile var accentAtStart = 0
        @Volatile var lightAtStart = false
    }
}

/**
 * Chaque appareil garde son apparence ; le téléphone demande avant de prendre celle du robot, et propose
 * d'appliquer au robot ce qu'on choisit ici. De bout en bout : vrai hub, vraie liaison, vraie activité.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SimLookApp::class)
class RobotLookEndToEndTest {
    private val app get() = RuntimeEnvironment.getApplication() as CompanionApp
    private val robot get() = SimLookApp.robot!!

    @After fun tearDown() {
        app.link.close()
        robot.shutdown()
        Ui.setLook("")
    }

    private fun until(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (cond()) return
            assertTrue("délai dépassé : $what", System.currentTimeMillis() < end)
            Thread.sleep(20)
        }
    }

    private fun <T : View> find(root: View, type: Class<T>, cond: (T) -> Boolean = { true }): T? {
        if (type.isInstance(root) && cond(type.cast(root)!!)) return type.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), type, cond)?.let { return it }
        return null
    }

    private fun sheet(a: CompanionActivity): ChoiceSheet? = find(a.shell, ChoiceSheet::class.java)

    private fun click(root: View, label: String) {
        var v: View = find(root, TextView::class.java) { it.text.toString() == label }!!
        while (!v.isClickable) v = v.parent as View
        v.performClick()
    }

    private fun recolour(accent: String, theme: String) =
        robot.hub.updateRobot(RobotInfo(SimLookApp.NAME, "0.8.1", "fr", accent = accent, theme = theme))

    private fun settle(ms: Long = 1_500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    @Test fun eachDeviceKeepsItsLookAndThePhoneAsksFirst() {
        // Au démarrage, avant tout état : l'apparence du téléphone (ciel, claire), pas celle du robot.
        assertEquals(0xFF4FC3F7.toInt(), SimLookApp.accentAtStart)
        assertTrue(SimLookApp.lightAtStart)
        val a = Robolectric.buildActivity(CompanionActivity::class.java).setup().get()
        assertEquals(0xFFF3F5F8.toInt(), Ui.BG)
        assertEquals(Ui.BG, a.window.statusBarColor)
        assertEquals(emptyList<String>(), Looks.find(a.shell, Looks.themeColours(Palette.DARK) - Looks.themeColours(Palette.LIGHT)))
        until("connecté") { app.link.status.phase == LinkPhase.CONNECTED && app.link.latest != null }

        // Une recette en cours d'écriture : jamais de question par-dessus l'éditeur.
        until("droit d'écrire") { app.link.latest?.can?.editRecipes?.allowed == true }
        a.shell.select(Tab.RECIPES)
        a.catalog.ensureLoaded()
        until("catalogue") { a.screens.recettes.state?.canWrite == true }
        a.catalog.writeRecipe()
        assertEquals(EditPhase.EDITING, a.screens.editeur.state?.phase)
        val title = find(a.screens.editeur.view, EditText::class.java) { it.contentDescription == tr("Titre") }!!
        title.setText("Soupe du jour")
        val drafts = File(app.noBackupFilesDir, "brouillons")
        until("brouillon écrit") { drafts.listFiles()?.any { it.name.endsWith(".json") && it.readText().contains("Soupe du jour") } == true }
        val draft = drafts.listFiles()!!.single { it.name.endsWith(".json") }
        val bytes = draft.readBytes()
        settle()
        assertNull(sheet(a))
        // Le robot passe au citron et au sombre pendant l'écriture : pas de question tant que l'éditeur est là.
        recolour("#E8D44D", "dark")
        until("nouvelle apparence du robot reçue") { app.link.latest?.robot?.accent == "#E8D44D" }
        a.appearance.check()
        settle()
        assertNull("question par-dessus l'éditeur", sheet(a))
        assertEquals(0xFF4FC3F7.toInt(), Ui.ACCENT)

        // Sur un écran calme (onglet Robot) : la question, avec deux interrupteurs (couleur, thème).
        val editor = a.screens.editeur
        a.shell.select(Tab.ROBOT)
        until("question") { sheet(a) != null }
        val q = sheet(a)!!
        assertNotNull(find(q, TextView::class.java) { it.text.toString() == tr("Le robot a une autre apparence") })
        // Ne prendre que la couleur : le thème du téléphone reste clair.
        find(q, TextView::class.java) { it.contentDescription == tr("Mode sombre") }!!.performClick()
        click(q, tr("Appliquer"))
        assertNull(sheet(a))
        assertEquals(0xFFE8D44D.toInt(), Ui.ACCENT)
        assertTrue(Ui.light)
        assertEquals("#E8D44D", app.lookStore.accent)
        assertEquals("light", app.lookStore.theme)
        // L'éditeur, caché, est refait aux nouvelles couleurs avec son texte ; le brouillon n'a pas bougé.
        assertNotSame(editor, a.screens.editeur)
        assertEquals("Soupe du jour", find(a.screens.editeur.view, EditText::class.java) { it.contentDescription == tr("Titre") }!!.text.toString())
        assertTrue(bytes.contentEquals(draft.readBytes()))

        // Plus de question pour cette apparence du robot.
        a.shell.select(Tab.LOG)
        a.shell.select(Tab.ROBOT)
        settle()
        assertNull(sheet(a))

        // Le robot change de couleur : une question (une seule différence : pas d'interrupteur). « Garder le mien ».
        recolour("#F2639A", "light")
        until("seconde question") { sheet(a) != null }
        assertNull(find(sheet(a)!!, TextView::class.java) { it.contentDescription == tr("Sa couleur") })
        click(sheet(a)!!, tr("Garder le mien"))
        assertEquals(0xFFE8D44D.toInt(), Ui.ACCENT)
        a.shell.select(Tab.LOG)
        a.shell.select(Tab.ROBOT)
        settle()
        assertNull("« Garder le mien » est retenu", sheet(a))

        // Choisi ici (onglet Robot › Apparence) : « Appliquer aussi au robot ? », oui : le robot la prend.
        until("permission du robot") { app.link.latest?.can?.setAppearance?.allowed == true }
        a.robot.chooseAccent(0xFF8BD85F.toInt())
        assertEquals(0xFF8BD85F.toInt(), Ui.ACCENT)
        until("question pour le robot") { sheet(a) != null }
        assertNotNull(find(sheet(a)!!, TextView::class.java) { it.text.toString() == tr("Appliquer aussi au robot ?") })
        click(sheet(a)!!, tr("Oui"))
        until("robot à la même couleur") { app.link.latest?.robot?.accent == "#8BD85F" }
        assertEquals(listOf("#8BD85F:null"), robot.lookRequests.toList())
        settle()
        assertNull("rien à demander : même couleur", sheet(a))

        // Robot occupé : son refus, montré tel quel ; rien n'a changé sur le robot. Le téléphone, lui, est passé au sombre.
        robot.lookBusy = true
        a.robot.chooseTheme(false)
        assertFalse(Ui.light)
        assertEquals(0xFF0A1322.toInt(), Ui.BG)
        assertEquals(Ui.BG, a.window.statusBarColor)
        assertEquals(emptyList<String>(), Looks.find(a.shell, Looks.themeColours(Palette.LIGHT) - Looks.themeColours(Palette.DARK)))
        until("question pour le robot (thème)") { sheet(a) != null }
        click(sheet(a)!!, tr("Oui"))
        until("refus montré") { a.shell.noticeText != null }
        assertEquals("Le robot n'est pas sur son écran d'accueil : revenez-y sur le robot, puis réessayez", a.shell.noticeText)
        assertEquals("light", app.link.latest!!.robot.theme)
        assertEquals(listOf("#8BD85F:null", "null:dark"), robot.lookRequests.toList())
        assertTrue(bytes.contentEquals(draft.readBytes()))
        assertEquals(Route.ROBOT, a.shell.current)
    }

    /**
     * Une couleur de la roue dans les deux sens : prise du robot (question avec sa pastille), puis choisie
     * ici et proposée au robot qui le dit (robot.anyAccent) ; un robot d'avant la roue ne prend que les
     * dix : rien ne lui est proposé pour une couleur de la roue (il répondrait 400), les dix toujours.
     */
    @Test fun aWheelColourGoesBothWaysButOnlyToARobotThatTakesIt() {
        val a = Robolectric.buildActivity(CompanionActivity::class.java).setup().get()
        until("connecté") { app.link.status.phase == LinkPhase.CONNECTED && app.link.latest != null }
        until("permission du robot") { app.link.latest?.can?.setAppearance?.allowed == true }
        assertTrue(app.link.latest!!.robot.anyAccent)
        a.shell.select(Tab.ROBOT)
        settle(500)

        // Le robot prend une couleur de la roue : la question montre sa pastille ; « Appliquer ».
        val fromRobot = ColourWheel.colour(150.0, 0.8)
        recolour(AppearanceFlow.hexOf(fromRobot), "light")
        until("question") { sheet(a) != null }
        assertEquals(fromRobot, find(sheet(a)!!, Swatch::class.java)!!.color)
        click(sheet(a)!!, tr("Appliquer"))
        assertEquals(fromRobot, Ui.ACCENT)
        assertEquals(AppearanceFlow.hexOf(fromRobot), app.lookStore.accent)

        // Choisie ici, sur la roue : « Appliquer aussi au robot ? » avec sa pastille ; oui : le robot la prend.
        val here = ColourWheel.colour(20.0, 1.0)
        a.robot.chooseAccent(here)
        until("question pour le robot") { sheet(a) != null }
        assertNotNull(find(sheet(a)!!, TextView::class.java) { it.text.toString() == tr("Appliquer aussi au robot ?") })
        assertEquals(here, find(sheet(a)!!, Swatch::class.java)!!.color)
        click(sheet(a)!!, tr("Oui"))
        val hex = AppearanceFlow.hexOf(here)
        until("robot à la couleur de la roue") { app.link.latest?.robot?.accent == hex }
        assertEquals(listOf("$hex:null"), robot.lookRequests.toList())

        // Un robot d'avant la roue (les dix seulement) : il le dit en n'écrivant plus robot.anyAccent.
        settle()
        robot.tenColoursOnly = true
        recolour("#E8D44D", "light")
        until("robot sans la roue") { app.link.latest?.robot?.let { !it.anyAccent && it.accent == "#E8D44D" } == true }
        a.appearance.check()
        until("question") { sheet(a) != null }
        click(sheet(a)!!, tr("Garder le mien"))
        settle(500)
        // Différents, mais une couleur de la roue : pas de « Appliquer au robot », pas de question.
        assertTrue(a.appearance.state().canTake)
        assertFalse(a.appearance.state().canSend)
        a.robot.chooseAccent(ColourWheel.colour(200.0, 0.6))
        settle()
        assertNull("rien de proposé au robot pour une couleur de la roue", sheet(a))
        a.appearance.sendToRobot()
        settle(500)
        assertEquals(1, robot.lookRequests.size)
        // Une des dix : proposée comme avant.
        a.robot.chooseAccent(0xFF8BD85F.toInt())
        until("question pour le robot (une des dix)") { sheet(a) != null }
        click(sheet(a)!!, tr("Oui"))
        until("robot au pistache") { app.link.latest?.robot?.accent == "#8BD85F" }
        assertEquals(listOf("$hex:null", "#8BD85F:null"), robot.lookRequests.toList())
    }
}
