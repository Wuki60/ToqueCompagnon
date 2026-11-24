package app.toque.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les teintes de la couleur du robot, sur la JVM : l'orange redonne au pixel près les couleurs d'avant,
 * les dix couleurs du robot restent lisibles sur les fonds sombres du téléphone, une couleur trop sombre
 * venue du fil est éclaircie.
 */
class AccentTest {
    // Fonds sombres du téléphone (Ui.BG, Ui.SURFACE, Ui.SURFACE2) et texte clair (Ui.TEXT).
    private val bg = 0xFF0A1322.toInt()
    private val surface = 0xFF131D2E.toInt()
    private val surface2 = 0xFF1A2639.toInt()
    private val text = 0xFFF3F5F9.toInt()
    private val surfaces = intArrayOf(bg, surface, surface2)

    /** Les dix couleurs de Réglages › Apparence du robot (app/…/ui/Ui.kt, ACCENTS). */
    private val robotAccents = intArrayOf(
        0xFFFF8A3D.toInt(), 0xFFFFB547.toInt(), 0xFFE8D44D.toInt(), 0xFF8BD85F.toInt(), 0xFF3DDCB0.toInt(),
        0xFF4FC3F7.toInt(), 0xFF6E9BFF.toInt(), 0xFFB48CFF.toInt(), 0xFFF2639A.toInt(), 0xFFFF6F61.toInt(),
    )

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    @Test fun orangeGivesTheShadesOfTheMockupPixelForPixel() {
        val s = Accent.shades(Accent.DEFAULT, bg, surface)
        assertEquals(0xFFFF8A3D.toInt(), s.accent)
        assertEquals(0x33FF8A3D, s.dim)
        assertEquals(hex(0xFF78492E.toInt()), hex(s.done))
        assertEquals(hex(0xFF603D2B.toInt()), hex(s.soft))
        assertEquals(hex(0xFFA15E37.toInt()), hex(s.line))
        assertEquals(0xFF78492E.toInt(), s.done)
        assertEquals(0xFF603D2B.toInt(), s.soft)
        assertEquals(0xFFA15E37.toInt(), s.line)
        // Texte sur l'orange : celui de la maquette, pas la règle du robot (#1E1007, 4/2/2 plus clair).
        assertEquals(0xFF1A0E05.toInt(), s.onAccent)
        assertEquals(Accent.DEFAULT, Accent.readable(Accent.DEFAULT, surfaces))
    }

    @Test fun theRobotRuleOnOrangeLandsJustNextToTheMockup() {
        // 12 % de chaque canal de #FF8A3D : #1E1007. Les deux sont largement lisibles.
        val rule = Accent.robotRule(Accent.DEFAULT)
        assertEquals("#1E1007", hex(rule))
        assertEquals(7.91, Accent.contrast(rule, Accent.DEFAULT), 0.01)
        assertEquals(8.08, Accent.contrast(Accent.ON_DEFAULT, Accent.DEFAULT), 0.01)
    }

    @Test fun theTenRobotColoursStayAsTheyAreAndReadable() {
        for (a in robotAccents) {
            assertEquals(hex(a), hex(Accent.readable(a, surfaces)))
            val s = Accent.shades(a, bg, surface)
            for (back in surfaces) assertTrue("${hex(a)} sur ${hex(back)} : ${Accent.contrast(a, back)}", Accent.contrast(a, back) >= 4.5)
            assertTrue("texte sur ${hex(a)}", Accent.contrast(s.onAccent, a) >= 4.5)
            // Texte clair sur le fond discret (pastille « En attente ») et coche sur l'étape faite.
            assertTrue("texte sur le fond discret de ${hex(a)}", Accent.contrast(text, s.soft) >= 4.5)
            assertTrue("coche sur l'étape faite de ${hex(a)}", Accent.contrast(text, s.done) >= 3.0)
            // Le contour reste distinct de la carte, l'étape faite du fond.
            assertTrue(Accent.contrast(s.line, surface) >= 1.5)
            assertTrue(Accent.contrast(s.done, bg) >= 1.5)
            assertEquals(0x33, s.dim ushr 24)
            assertEquals(a and 0xFFFFFF, s.dim and 0xFFFFFF)
        }
    }

    @Test fun otherColoursFollowTheRobotRuleForTheTextOnTheAccent() {
        // Règle du robot (Ui.setAccent) : 12 % de chaque canal, arrondi vers le bas.
        assertEquals(0xFF09171D.toInt(), Accent.onAccent(0xFF4FC3F7.toInt()))
        assertEquals(0xFF1B1909.toInt(), Accent.onAccent(0xFFE8D44D.toInt()))
        assertEquals(0xFF1D0B12.toInt(), Accent.onAccent(0xFFF2639A.toInt()))
    }

    @Test fun aDarkColourFromTheWireIsLightenedUntilReadable() {
        val navy = 0xFF1A237E.toInt()
        assertTrue(Accent.contrast(navy, surface2) < 2.0)
        val r = Accent.readable(navy, surfaces)
        assertEquals("#8D91BF", hex(r))
        for (back in surfaces) assertTrue(Accent.contrast(r, back) >= 4.5)
        assertTrue(Accent.contrast(Accent.onAccent(r), r) >= 4.5)
        // Toujours un bleu : le bleu reste le canal le plus fort.
        assertTrue((r and 0xFF) > ((r shr 16) and 0xFF) && (r and 0xFF) > ((r shr 8) and 0xFF))
        // Noir : un gris lisible ; rouge pur : un rouge plus clair ; blanc : tel quel.
        val black = Accent.readable(0xFF000000.toInt(), surfaces)
        for (back in surfaces) assertTrue(Accent.contrast(black, back) >= 4.5)
        assertEquals("#FF4D4D", hex(Accent.readable(0xFFFF0000.toInt(), surfaces)))
        assertEquals("#FFFFFF", hex(Accent.readable(0xFFFFFFFF.toInt(), surfaces)))
        val white = Accent.shades(0xFFFFFFFF.toInt(), bg, surface)
        assertTrue(Accent.contrast(white.onAccent, white.accent) >= 4.5)
        assertTrue(Accent.contrast(text, white.soft) >= 4.5)
    }

    @Test fun onlyAColourIsRead() {
        assertEquals(0xFF4FC3F7.toInt(), Accent.parse("#4FC3F7"))
        assertEquals(0xFF4FC3F7.toInt(), Accent.parse("#4fc3f7"))
        for (bad in listOf(null, "", "4FC3F7", "#4FC3F", "#4FC3F7FF", "#GGGGGG", "rouge", " #4FC3F7")) assertNull(bad, Accent.parse(bad))
        assertNotEquals(Accent.DEFAULT, Accent.parse("#000000"))
    }

    @Test fun blendAndContrastFollowWcag() {
        assertEquals(21.0, Accent.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Accent.contrast(bg, bg), 0.0001)
        assertEquals(bg, Accent.blend(bg, Accent.DEFAULT, 0.0))
        assertEquals(Accent.DEFAULT, Accent.blend(bg, Accent.DEFAULT, 1.0))
    }
}
