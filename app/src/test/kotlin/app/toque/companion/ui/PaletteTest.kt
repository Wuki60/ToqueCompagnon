package app.toque.companion.ui

import app.toque.core.look.ColourWheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les deux thèmes du téléphone, sur la JVM : le sombre avec l'orange redonne au pixel près les couleurs
 * d'avant ; sur les deux thèmes et avec chacune des dix couleurs du robot, chaque paire texte ou icône /
 * fond que les écrans emploient garde 4,5:1 (3:1 pour les icônes qui décorent).
 */
class PaletteTest {
    private val accents = intArrayOf(
        0xFFFF8A3D.toInt(), 0xFFFFB547.toInt(), 0xFFE8D44D.toInt(), 0xFF8BD85F.toInt(), 0xFF3DDCB0.toInt(),
        0xFF4FC3F7.toInt(), 0xFF6E9BFF.toInt(), 0xFFB48CFF.toInt(), 0xFFF2639A.toInt(), 0xFFFF6F61.toInt(),
    )
    private val white = 0xFFFFFFFF.toInt()

    private fun hex(c: Int) = String.format("#%08X", c)

    private fun atLeast(min: Double, fg: Int, bg: Int, what: String, out: MutableList<String>) {
        val r = Accent.contrast(fg, bg)
        if (r < min) out += "$what : ${hex(fg)} sur ${hex(bg)} = ${"%.2f".format(r)} (< $min)"
    }

    @Test fun darkWithOrangeIsTheMockupPixelForPixel() {
        val p = Palette.DARK
        val s = Accent.look(null, p)
        assertEquals(0xFFFF8A3D.toInt(), s.accent)
        assertEquals(0xFFFF8A3D.toInt(), s.ink)
        assertEquals(0x33FF8A3D, s.dim)
        assertEquals(0xFF78492E.toInt(), s.done)
        assertEquals(0xFF603D2B.toInt(), s.soft)
        assertEquals(0xFFA15E37.toInt(), s.line)
        assertEquals(0xFF1A0E05.toInt(), s.onAccent)
        assertEquals(0xFF312F29.toInt(), p.warnSoft)
        assertEquals(0xFF7D673C.toInt(), p.warnLine)
        assertEquals(0xFF8492A8.toInt(), p.textMuted)
        assertEquals(s, Accent.look(Accent.DEFAULT, p))
        assertEquals(Palette.DARK, Palette.of(""))
        assertEquals(Palette.DARK, Palette.of("dark"))
        assertEquals(Palette.DARK, Palette.of("sepia"))
        assertEquals(Palette.LIGHT, Palette.of("light"))
    }

    @Test fun theLightPaletteIsTheRobots() {
        val p = Palette.LIGHT
        assertEquals(listOf(0xFFF3F5F8, 0xFFFFFFFF, 0xFFEBEEF3, 0xFFDEE3EB, 0xFFC5CDD9, 0xFF1A2332, 0xFF5A6578).map { it.toInt() },
            listOf(p.bg, p.surface, p.surface2, p.surface3, p.stroke, p.text, p.text2))
        assertEquals(listOf(0xFF0B6FA8, 0xFF10794D, 0xFF9A5B00, 0xFFC62828).map { it.toInt() }, listOf(p.cool, p.ok, p.warn, p.danger))
        assertEquals(white, p.onCool)
        assertEquals(white, p.onWarn)
        assertEquals(0x99000000.toInt(), p.scrim)
    }

    /** Toutes les paires des écrans, sur les deux thèmes, avec les dix couleurs. */
    @Test fun everyPairIsReadableOnBothThemesWithTheTenColours() {
        val out = ArrayList<String>()
        for (p in listOf(Palette.DARK, Palette.LIGHT)) {
            val t = if (p.light) "clair" else "sombre"
            // Le thème lui-même.
            for (back in intArrayOf(p.bg, p.surface, p.surface2, p.surface3)) {
                atLeast(4.5, p.text, back, "$t texte", out)
                atLeast(4.5, p.text2, back, "$t texte secondaire", out)
                // Indications discrètes (exemples dans les champs, listes vides) : 3:1, comme sur le sombre.
                if (back != p.surface3) atLeast(3.0, p.text3, back, "$t texte tertiaire", out)
            }
            for (back in intArrayOf(p.bg, p.surface)) atLeast(4.5, p.textMuted, back, "$t texte atténué", out)
            for (state in intArrayOf(p.cool, p.ok, p.warn, p.danger)) for (back in intArrayOf(p.bg, p.surface, p.surface2))
                atLeast(4.5, state, back, "$t état", out)
            atLeast(4.5, p.onCool, p.cool, "$t texte sur COOL", out)
            atLeast(4.5, p.onWarn, p.warn, "$t texte sur WARN", out)
            // Sombre : 3,05:1, comme le robot (bouton rouge, icône ou mot court en gras) ; inchangé, signalé.
            atLeast(if (p.light) 4.5 else 3.0, white, p.danger, "$t blanc sur DANGER", out)
            atLeast(4.5, p.warn, p.warnSoft, "$t avertissement sur son fond", out)
            atLeast(4.5, p.text, p.warnSoft, "$t texte sur le fond d'avertissement", out)
            // Posé sur une photo : voile sombre, texte clair, quel que soit le thème (pire cas : photo blanche).
            atLeast(4.5, Ui.ON_PHOTO, Accent.blend(white, 0xFF000000.toInt(), (Ui.PHOTO_VEIL ushr 24) / 255.0), "$t texte sur photo", out)
            atLeast(4.5, Ui.CAMERA_TEXT, Ui.CAMERA_BG, "$t texte de la caméra", out)

            for (a in accents) {
                val s = Accent.look(a, p)
                val n = "$t ${hex(a)}"
                // L'accent en texte ou icône : sur chaque surface et sur son propre fond discret.
                for (back in intArrayOf(p.bg, p.surface, p.surface2, p.surface3, Accent.blend(p.surface2, a, 0x33 / 255.0)))
                    atLeast(4.5, s.ink, back, "$n encre", out)
                // L'accent en fond : bouton, puce choisie, pastille pleine.
                atLeast(4.5, s.onAccent, s.accent, "$n texte sur l'accent", out)
                atLeast(4.5, p.text, s.soft, "$n texte sur le fond discret", out)
                atLeast(3.0, p.text, s.done, "$n coche sur l'étape faite", out)
                if (!p.light) assertEquals(n, a, s.accent)
                if (p.light) assertEquals("$n : le fond garde la couleur choisie", a, s.accent)
            }
        }
        assertTrue(out.joinToString("\n"), out.isEmpty())
    }

    /**
     * Les couleurs de la roue (ColourWheel), sur tout l'anneau : chaque paire reste lisible sur les deux
     * thèmes. Sur le sombre, la couleur est gardée telle quelle, sauf un léger éclaircissement quand elle
     * frôle 4,5:1 sur les surfaces plus claires que celle des cartes (Accent.readable) ; sur le clair, le
     * fond garde la couleur choisie et l'encre en est une version foncée.
     */
    @Test fun everyPairIsReadableWithTheWheelColours() {
        val out = ArrayList<String>()
        var lightened = 0
        var total = 0
        for (p in listOf(Palette.DARK, Palette.LIGHT)) {
            val t = if (p.light) "clair" else "sombre"
            var h = 0.0
            while (h < 360.0) {
                for (k in 0..10) {
                    val a = ColourWheel.colour(h, k / 10.0)
                    val s = Accent.look(a, p)
                    val n = "$t ${hex(a)} (teinte $h, intensité ${k / 10.0})"
                    for (back in intArrayOf(p.bg, p.surface, p.surface2, p.surface3, Accent.blend(p.surface2, a, 0x33 / 255.0)))
                        atLeast(4.5, s.ink, back, "$n encre", out)
                    atLeast(4.5, s.onAccent, s.accent, "$n texte sur l'accent", out)
                    atLeast(4.5, p.text, s.soft, "$n texte sur le fond discret", out)
                    atLeast(3.0, p.text, s.done, "$n coche sur l'étape faite", out)
                    if (p.light) assertEquals("$n : le fond garde la couleur choisie", a, s.accent)
                    else {
                        total++
                        if (s.accent != a) {
                            lightened++
                            // Éclaircie d'un pas au plus (5 % vers le blanc).
                            assertEquals(n, Accent.blend(a, white, 0.05), s.accent)
                        }
                    }
                }
                h += 3.0
            }
        }
        assertTrue(out.joinToString("\n"), out.isEmpty())
        println("roue sur le sombre : $lightened couleurs sur $total éclaircies d'un pas")
    }

    @Test fun inkStaysTheSameHueAndOnlyMovesWhenItMust() {
        // Sombre : l'encre est l'accent pour la plupart ; framboise s'éclaircit un peu (4,44:1 sur SURFACE3 sinon).
        assertEquals(0xFFE8D44D.toInt(), Accent.look(0xFFE8D44D.toInt(), Palette.DARK).ink)
        val raspberry = Accent.look(0xFFF2639A.toInt(), Palette.DARK).ink
        assertTrue(raspberry != 0xFFF2639A.toInt())
        // Clair : plus foncée, même teinte (le canal dominant reste le même).
        for (a in accents) {
            val ink = Accent.look(a, Palette.LIGHT).ink
            assertTrue(Accent.luminance(ink) < Accent.luminance(a))
            fun top(c: Int) = listOf(16, 8, 0).maxByOrNull { (c shr it) and 0xFF }
            assertEquals(hex(a), top(a), top(ink))
        }
        // Une couleur sombre venue d'ailleurs, sur le clair : gardée, texte blanc dessus.
        val navy = Accent.look(0xFF1A237E.toInt(), Palette.LIGHT)
        assertEquals(0xFF1A237E.toInt(), navy.accent)
        assertEquals(white, navy.onAccent)
    }
}
