package app.toque.companion

import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import app.toque.companion.ui.PictureSource
import app.toque.companion.ui.Thumbnail
import app.toque.companion.ui.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** La vignette : une demande finie sans photo est oubliée, la suivante vient d'un bind ou d'un attach, jamais du rappel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbnailTest {
    private lateinit var c: ContextThemeWrapper

    /** Source de photos tenue à la main : chaque demande est gardée, avec ses annulations. */
    private class Source : PictureSource {
        val asked = ArrayList<Pair<String, (Bitmap?) -> Unit>>()
        var cancelled = 0
        /** Réponse immédiate (en mémoire ou 404 retenu), sinon la demande attend. */
        var answerAtOnce: Boolean? = null

        override fun load(recipeId: String, widthPx: Int, onLoaded: (Bitmap?) -> Unit): PictureSource.Request {
            asked += recipeId to onLoaded
            when (answerAtOnce) {
                true -> onLoaded(Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888))
                false -> onLoaded(null)
                null -> {}
            }
            return PictureSource.Request { cancelled++ }
        }
    }

    @Before fun setUp() {
        c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Companion)
        Ui.init(c)
    }

    @Test fun aRequestEndedWithoutPhotoIsAskedAgainOnTheNextBindOnly() {
        val src = Source()
        val t = Thumbnail(c, 20f, 34f)
        t.bind("a", "Soupe", true, src, 180)
        assertEquals(1, src.asked.size)
        // Même recette, demande en cours : rien de plus.
        t.bind("a", "Soupe", true, src, 180)
        assertEquals(1, src.asked.size)
        // Finie sans photo (robot injoignable) : pas de nouvelle demande depuis le rappel…
        src.asked[0].second(null)
        assertEquals(1, src.asked.size)
        // … mais au prochain bind de la même recette (page suivante de la grille).
        t.bind("a", "Soupe", true, src, 180)
        assertEquals(2, src.asked.size)
        // Elle arrive : affichée, plus redemandée.
        src.asked[1].second(Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888))
        assertNotNull(t.image.drawable)
        t.bind("a", "Soupe", true, src, 180)
        assertEquals(2, src.asked.size)
        assertEquals(0, src.cancelled)
    }

    @Test fun aRememberedMissingPhotoIsAnsweredAtOnceWithoutLooping() {
        val src = Source().apply { answerAtOnce = false }
        val t = Thumbnail(c, 20f, 34f)
        t.bind("a", "Soupe", true, src, 180)
        assertEquals(1, src.asked.size)
        assertNull(t.image.drawable)
        // Chaque bind redemande une fois (réponse immédiate de la file, sans réseau), jamais plus.
        t.bind("a", "Soupe", true, src, 180)
        assertEquals(2, src.asked.size)
        // Une autre recette : l'ancienne demande (finie) n'est pas annulée.
        t.bind("b", "Gratin", true, src, 180)
        assertEquals(3, src.asked.size)
        assertEquals(0, src.cancelled)
    }

    @Test fun aRequestInFlightIsCancelledWhenTheCardShowsAnotherRecipe() {
        val src = Source()
        val t = Thumbnail(c, 20f, 34f)
        t.bind("a", "Soupe", true, src, 180)
        t.bind("b", "Gratin", true, src, 180)
        assertEquals(1, src.cancelled)
        // La réponse tardive de « a » est ignorée.
        src.asked[0].second(Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888))
        assertNull(t.image.drawable)
        // En mémoire : affichée pendant le bind.
        src.answerAtOnce = true
        t.bind("c", "Tarte", true, src, 180)
        assertNotNull(t.image.drawable)
        assertEquals(2, src.cancelled)
    }
}
