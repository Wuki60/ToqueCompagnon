package app.toque.companion.flows

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Décodage à la taille affichée (Robolectric, graphismes natifs) : une photo du robot, JPEG 800 × 600. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RecipePicturesDecodeTest {
    private val jpeg: ByteArray = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).let { b ->
        b.eraseColor(Color.rgb(200, 120, 40))
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    @Test fun aCardKeepsOnlyItsWidth() {
        // Carte : 467 px demandés, arrondis à 512 par le chargeur.
        val card = RecipePictures.decode(jpeg, PhotoLoader.bucket(467))!!
        assertEquals(512, card.width)
        assertEquals(384, card.height)
        assertTrue(card.byteCount <= 512 * 384 * 4)
        // Petite vignette (Envoyée, 56 dp) : réduction par puissance de 2 puis mise à l'échelle.
        val small = RecipePictures.decode(jpeg, 192)!!
        assertEquals(192, small.width)
        assertEquals(144, small.height)
    }

    @Test fun neverLargerThanTheOriginal() {
        val fiche = RecipePictures.decode(jpeg, 1024)!!
        assertEquals(800, fiche.width)
        assertEquals(600, fiche.height)
    }

    @Test fun unreadableBytesAreRefused() {
        assertTrue(RecipePictures.readable(jpeg))
        assertFalse(RecipePictures.readable(byteArrayOf(1, 2, 3)))
        assertNull(RecipePictures.decode(byteArrayOf(1, 2, 3), 512))
        assertFalse(RecipePictures.readable(jpeg.copyOf(10)))
    }
}
