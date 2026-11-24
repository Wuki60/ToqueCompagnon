package app.toque.companion

import android.util.Size
import app.toque.companion.scan.QrDecoder
import app.toque.companion.scan.QrScannerView
import app.toque.core.qr.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/**
 * Le décodage du lecteur de QR code, sans appareil photo : des codes faits par l'encodeur du
 * projet (core/…/qr/QrCode.kt) sont peints dans des images de luminance comme celles du plan Y
 * d'une image YUV_420_888 (lignes plus longues que l'image, contraste d'un écran filmé, bruit,
 * flou léger), à plusieurs tailles, dans les quatre rotations, puis lus par [QrDecoder].
 */
class QrDecoderTest {
    private val pairing = "toque://pair?v=1&h=192.168.0.12&p=8088&s=0123456789abcdef0123456789abcdef&r=a1b2c3d4e5f6&n=Monsieur%20Cuisine%20Connect"

    /** Image de luminance de [w] × [h], lignes de [stride] octets, avec le code peint au centre. */
    private class Frame(val w: Int, val h: Int, val stride: Int, val data: ByteArray)

    /**
     * Peint [qr] avec des modules de [modulePx] pixels (non entier possible), tourné de
     * [rotation] quarts de tour, centré puis décalé de ([dx], [dy]), avec la zone de silence ;
     * clair ≈ [light], foncé ≈ [dark], bruit d'amplitude [noise], dégradé d'éclairage, flou 3 × 3 si [blur].
     */
    private fun paint(qr: QrCode, w: Int, h: Int, modulePx: Float, rotation: Int, seed: Long,
                      noise: Int = 18, light: Int = 205, dark: Int = 45, blur: Boolean = true, dx: Int = 0, dy: Int = 0,
                      padding: Int = 48): Frame {
        val stride = w + padding
        val rnd = Random(seed)
        val n = qr.size
        val total = n + 2 * QrCode.QUIET_ZONE
        val side = total * modulePx
        val ox = (w - side) / 2f + dx
        val oy = (h - side) / 2f + dy
        val lum = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            // Fond : un mur un peu plus sombre que l'écran du robot, éclairage qui baisse vers la droite.
            var v = 120 - x * 30 / w
            val fx = (x - ox) / modulePx
            val fy = (y - oy) / modulePx
            if (fx >= 0 && fy >= 0 && fx < total && fy < total) {
                var mx = fx.toInt() - QrCode.QUIET_ZONE
                var my = fy.toInt() - QrCode.QUIET_ZONE
                // Rotation du code (quarts de tour dans le sens horaire).
                repeat(rotation and 3) { val t = mx; mx = my; my = n - 1 - t }
                val isDark = mx in 0 until n && my in 0 until n && qr.isDark(mx, my)
                v = (if (isDark) dark else light) - x * 20 / w
            }
            lum[y * w + x] = v + (rnd.nextGaussian() * noise / 2).toInt()
        }
        val data = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = lum[y * w + x]
            if (blur && x in 1 until w - 1 && y in 1 until h - 1) {
                var s = 0
                for (j in -1..1) for (i in -1..1) s += lum[(y + j) * w + x + i]
                v = s / 9
            }
            data[y * stride + x] = v.coerceIn(0, 255).toByte()
        }
        // Octets de bourrage en bout de ligne : du bruit, qui ne doit pas gêner.
        for (y in 0 until h) for (x in w until stride) data[y * stride + x] = rnd.nextInt(256).toByte()
        return Frame(w, h, stride, data)
    }

    private fun read(f: Frame, crop: Float = 1f): String? = QrDecoder(crop).decode(f.data, f.w, f.h, f.stride)

    @Test fun readsThePairingCodeInEveryRotationAndSize() {
        val qr = QrCode.encode(pairing)
        var seed = 1L
        val failed = ArrayList<String>()
        val small = ArrayList<String>()
        // Tailles d'image du lecteur (1920 × 1080) et plus petites ; 3,5 pixels par module au moins
        // doivent passer, avec et sans flou. En dessous (2,5 et 3 px), relevé pour information.
        for ((w, h) in listOf(1920 to 1080, 1280 to 720, 640 to 480)) {
            val maxSide = minOf(w, h) * 0.9f / (qr.size + 2 * QrCode.QUIET_ZONE)
            for (module in listOf(2.5f, 3f, 3.5f, 4f, 4.6f, 7f, maxSide)) {
                for (rotation in 0..3) for (blur in listOf(false, true)) {
                    val f = paint(qr, w, h, module, rotation, seed++, blur = blur)
                    if (read(f) == pairing) continue
                    val what = "$w×$h, module $module px, rotation ${rotation * 90}°, flou $blur"
                    if (module < 3.5f) small += what else failed += what
                }
            }
        }
        println("non lus sous 3,5 px par module (information) :\n" + small.joinToString("\n"))
        assertEquals(emptyList<String>(), failed)
    }

    @Test fun readsShortAndLongTexts() {
        val long = "toque://pair?" + "x".repeat(180)
        for ((i, text) in listOf("482913", pairing, long).withIndex()) {
            val qr = QrCode.encode(text)
            val module = 640f * 0.85f / (qr.size + 2 * QrCode.QUIET_ZONE)
            for (rotation in 0..3) assertEquals("version ${qr.version}, rotation ${rotation * 90}°", text,
                read(paint(qr, 1280, 720, minOf(module, 6f), rotation, 100L + i * 4 + rotation)))
        }
    }

    @Test fun readsAnOffCentreCodeInsideTheFrame() {
        val qr = QrCode.encode(pairing)
        val f = paint(qr, 1280, 720, 4f, 1, 7, dx = 120, dy = -80)
        assertEquals(pairing, read(f))
    }

    @Test fun ignoresACodeOutsideTheVisibleSquare() {
        // Le code est à gauche, hors du carré central que l'aperçu montre dans le cadre.
        val qr = QrCode.encode(pairing)
        val f = paint(qr, 1280, 720, 3f, 0, 8, dx = -480)
        assertNull(read(f))
    }

    @Test fun returnsNullWithoutACode() {
        val rnd = Random(3)
        val w = 640
        val h = 480
        val data = ByteArray((w + 32) * h) { (100 + rnd.nextInt(60)).toByte() }
        assertNull(QrDecoder().decode(data, w, h, w + 32))
    }

    @Test fun readsALowContrastNoisyFrame() {
        val qr = QrCode.encode(pairing)
        val f = paint(qr, 1280, 720, 5f, 2, 11, noise = 30, light = 170, dark = 80)
        assertEquals(pairing, read(f))
    }
}

/** Choix des tailles de l'appareil photo (android.util.Size : Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrScannerSizeTest {
    @Test fun choosesTheLargestSizeUnderTheTargetWithTheSameAspect() {
        val sizes = listOf(Size(4000, 3000), Size(1920, 1080), Size(1280, 720), Size(1440, 1080), Size(640, 480), Size(320, 240))
        assertEquals(Size(1280, 720), QrScannerView.chooseSize(sizes, Size(1280, 720), null))
        // Aperçu du même rapport que les images décodées (16:9), au plus 1920 × 1080.
        assertEquals(Size(1920, 1080), QrScannerView.chooseSize(sizes, Size(1920, 1080), Size(1280, 720)))
        // Rapport 4:3 demandé.
        assertEquals(Size(1440, 1080), QrScannerView.chooseSize(sizes, Size(1920, 1080), Size(640, 480)))
        // Rien d'assez petit : la plus petite.
        assertEquals(Size(320, 240), QrScannerView.chooseSize(listOf(Size(320, 240), Size(640, 480)), Size(100, 100), null))
        assertNull(QrScannerView.chooseSize(emptyList(), Size(1280, 720), null))
    }
}
