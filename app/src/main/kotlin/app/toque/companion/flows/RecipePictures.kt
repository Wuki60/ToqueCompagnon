package app.toque.companion.flows

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.PictureSource
import app.toque.companion.ui.screens.RecipeCard
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Photos des recettes pour les écrans (Android) : [PhotoLoader] avec des [Bitmap]. Une seule
 * instance pour l'appli ([app.toque.companion.CompanionApp]) : la mémoire et le disque survivent à
 * l'activité. Le disque est `cacheDir/recipe-photos` (le système peut le vider s'il manque de place).
 * Décodage sur deux fils à elle, de faible priorité (le défilement passe avant).
 */
class RecipePictures(
    link: RobotLink,
    cacheDir: File,
    memoryBytes: Int = defaultMemoryBytes(),
) : PictureSource {
    private val main = Handler(Looper.getMainLooper())
    private val work: ExecutorService = Executors.newFixedThreadPool(2, ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "toque-photo").apply { isDaemon = true }
    })
    private val disk = PhotoDisk(File(cacheDir, "recipe-photos"), valid = ::readable)
    private val loader = PhotoLoader(link, disk, { main.post(it) }, work, { ms, r -> main.postDelayed(r, ms); { main.removeCallbacks(r) } },
        ::decode, { it.byteCount }, memoryBytes)

    override fun load(recipeId: String, widthPx: Int, onLoaded: (Bitmap?) -> Unit): PictureSource.Request =
        loader.load(recipeId, widthPx, onLoaded).let { r -> PictureSource.Request { r.cancel() } }

    /** Le catalogue affiché (chaque rendu de Recettes) : préchargement des photos des nouvelles pages. */
    fun catalogShown(items: List<RecipeCard>) = loader.catalogShown(items)

    /** Appli à l'écran : préchargement et vérifications en fond seulement à l'écran. */
    fun setVisible(visible: Boolean) = loader.setVisible(visible)

    /** `onTrimMemory` de l'appli : moitié rendue en quittant l'écran, tout quand la mémoire manque. */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> loader.trimMemory(0.0)
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> loader.trimMemory(0.5)
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> loader.trimMemory(0.0)
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> loader.trimMemory(0.5)
        else -> Unit
    }

    fun onLowMemory() = loader.trimMemory(0.0)

    companion object {
        /** Un huitième du tas de l'appli, entre 16 et 64 Mio. */
        fun defaultMemoryBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceIn(16L shl 20, 64L shl 20).toInt()

        /** Les dimensions se lisent : une image que [decode] saura ouvrir (gardée sur le disque seulement alors). */
        fun readable(bytes: ByteArray): Boolean {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            return o.outWidth > 0 && o.outHeight > 0
        }

        /**
         * Décode à [widthPx] de large (jamais plus que l'original) : réduction par puissance de 2 au
         * décodage, puis mise à l'échelle exacte, pour que la mémoire ne garde que ce qui s'affiche
         * (une carte : ≈ 512 px au lieu de 800 × 600). null si les octets sont illisibles.
         */
        fun decode(bytes: ByteArray, widthPx: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            val target = widthPx.coerceIn(1, o.outWidth)
            var sample = 1
            while (o.outWidth / (sample * 2) >= target) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            if (bmp.width <= target) return bmp
            val h = (bmp.height.toLong() * target / bmp.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, target, h, true)
            if (scaled !== bmp) bmp.recycle()
            return scaled
        }
    }
}
