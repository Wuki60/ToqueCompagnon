package app.toque.companion.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager

/**
 * Lecteur de QR code autonome : aperçu de l'appareil photo arrière (API camera2 du système, sans
 * CameraX) dans une TextureView, images décodées par [QrDecoder] sur un fil à part.
 *
 * - [start] quand l'écran est affiché (et la permission CAMERA accordée), [stop] quand il ne l'est
 *   plus ; les deux peuvent être appelés plusieurs fois. La vue s'arrête aussi quand elle quitte
 *   la fenêtre.
 * - [onText] reçoit, sur le fil principal, chaque texte lu (le même texte au plus toutes les
 *   [REPEAT_MS] ms) ; [onError] reçoit une raison courte si l'appareil photo ne peut pas servir
 *   (absent, refusé, occupé, erreur) : la vue s'est alors arrêtée.
 *
 * L'aperçu remplit la vue en la recadrant au centre ; seul le carré central de l'image est décodé.
 */
class QrScannerView(
    context: Context,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private var wanted = false

    /**
     * Fil de l'appareil photo, gardé tant que la vue est dans la fenêtre. Chaque ouverture a son
     * numéro ([generation]) : une réponse tardive d'une ouverture annulée ferme la caméra reçue.
     */
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var generation = 0
    /** Ouverture demandée au système, réponse pas encore reçue (fil de l'appareil photo). */
    private var opening = false
    /** La vue a quitté la fenêtre : finir le fil dès qu'aucune ouverture n'est en attente. */
    private var quitWhenIdle = false
    private var openedGeneration = -1

    // Tout ce qui suit n'est touché que sur le fil de l'appareil photo.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var luma = ByteArray(0)
    private var lastText: String? = null
    private var lastAt = 0L

    /** Taille de l'aperçu et orientation du capteur, pour le recadrage (fil principal). */
    private var preview: Size? = null
    private var sensorOrientation = 90

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    val running: Boolean get() = wanted

    fun start() {
        if (wanted) return
        wanted = true
        if (isAvailable) open()
    }

    fun stop() {
        if (!wanted) return
        wanted = false
        close()
    }

    override fun onDetachedFromWindow() {
        stop()
        val h = handler
        val t = thread
        handler = null
        thread = null
        h?.post { if (opening) quitWhenIdle = true else t?.quitSafely() }
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (wanted) open()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = transform()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        close()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun fail(reason: String, e: Throwable? = null, gen: Int = generation) {
        Log.w("Toque", "lecteur QR : $reason", e)
        main.post {
            if (!wanted || gen != generation) return@post
            wanted = false
            close()
            onError(reason)
        }
    }

    @SuppressLint("MissingPermission") // vérifiée juste avant
    private fun open() {
        if (openedGeneration == generation) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("permission")
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return fail("no-camera")
        val id: String
        val previewSize: Size
        val frameSize: Size
        try {
            val ids = manager.cameraIdList
            id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }
                ?: ids.firstOrNull() ?: return fail("no-camera")
            val ch = manager.getCameraCharacteristics(id)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fail("no-camera")
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            frameSize = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty(), FRAME_TARGET, null)
                ?: return fail("no-camera")
            previewSize = chooseSize(map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty(), PREVIEW_TARGET, frameSize)
                ?: return fail("no-camera")
        } catch (e: CameraAccessException) {
            return fail("camera-access", e)
        } catch (e: RuntimeException) {
            return fail("camera-error", e)
        }
        preview = previewSize
        transform()
        val h = handler ?: HandlerThread("qr-camera").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
        val gen = generation
        openedGeneration = gen
        val texture = surfaceTexture ?: return fail("no-surface")
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)
        val r = ImageReader.newInstance(frameSize.width, frameSize.height, ImageFormat.YUV_420_888, 2)
        val dec = QrDecoder()
        r.setOnImageAvailableListener({ decodeLatest(it, dec) }, h)
        try {
            // Sur le fil de l'appareil photo, après le nettoyage d'une fermeture précédente (ordre des messages).
            h.post { opening = true; reader = r }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                private fun answered() {
                    opening = false
                    if (quitWhenIdle) Looper.myLooper()?.quitSafely()
                }

                override fun onOpened(camera: CameraDevice) {
                    answered()
                    if (gen != generation || quitWhenIdle) { camera.close(); return }
                    device = camera
                    startSession(camera, surface, r, h)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    answered()
                    camera.close()
                    if (device === camera) device = null
                    fail("disconnected", gen = gen)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    answered()
                    camera.close()
                    if (device === camera) device = null
                    fail(if (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE) "in-use" else "camera-error-$error", gen = gen)
                }
            }, h)
        } catch (e: CameraAccessException) {
            fail("camera-access", e)
        } catch (e: SecurityException) {
            fail("permission", e)
        } catch (e: RuntimeException) {
            fail("camera-error", e)
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List…) : la seule forme disponible dès Android 8.
    private fun startSession(camera: CameraDevice, surface: Surface, r: ImageReader, h: Handler) {
        try {
            camera.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device !== camera) { s.close(); return }
                    session = s
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        req.addTarget(surface)
                        req.addTarget(r.surface)
                        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        s.setRepeatingRequest(req.build(), null, h)
                    } catch (e: CameraAccessException) {
                        fail("camera-access", e)
                    } catch (e: RuntimeException) {
                        fail("camera-error", e)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = fail("configure")
            }, h)
        } catch (e: CameraAccessException) {
            fail("camera-access", e)
        } catch (e: RuntimeException) {
            fail("camera-error", e)
        }
    }

    /** Fil de l'appareil photo : décode la dernière image (les plus anciennes sont abandonnées). */
    private fun decodeLatest(r: ImageReader, decoder: QrDecoder) {
        val image = try { r.acquireLatestImage() } catch (e: RuntimeException) { null } ?: return
        val text = try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val stride = plane.rowStride
            val need = stride * image.height
            if (luma.size < need) luma = ByteArray(need)
            buf.rewind()
            buf.get(luma, 0, minOf(buf.remaining(), need))
            decoder.decode(luma, image.width, image.height, stride)
        } catch (e: RuntimeException) {
            Log.w("Toque", "lecteur QR : image illisible", e)
            null
        } finally {
            image.close()
        }
        if (text.isNullOrEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < REPEAT_MS) return
        lastText = text
        lastAt = now
        main.post { if (wanted) onText(text) }
    }

    /** Ferme la caméra (sur son fil) ; le fil reste pour la prochaine ouverture. */
    private fun close() {
        generation++
        openedGeneration = -1
        val h = handler ?: return
        h.post {
            try { session?.close() } catch (e: RuntimeException) {}
            try { device?.close() } catch (e: RuntimeException) {}
            try { reader?.close() } catch (e: RuntimeException) {}
            session = null
            device = null
            reader = null
            lastText = null
        }
    }

    /** Recadrage au centre : l'aperçu remplit la vue sans être déformé. */
    private fun transform() {
        val p = preview ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        @Suppress("DEPRECATION")
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val displayDegrees = when (rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        // Le système tourne déjà l'image du capteur pour l'affichage : il reste à la recadrer.
        val upright = (sensorOrientation - displayDegrees + 360) % 180 != 0
        val cw = if (upright) p.height.toFloat() else p.width.toFloat()
        val ch = if (upright) p.width.toFloat() else p.height.toFloat()
        val sx = vw / cw
        val sy = vh / ch
        val s = maxOf(sx, sy)
        val m = Matrix()
        m.setScale(s / sx, s / sy, vw / 2f, vh / 2f)
        setTransform(m)
    }

    companion object {
        /** Le même texte n'est renvoyé qu'après ce délai (le code reste devant l'objectif). */
        const val REPEAT_MS = 2000L
        /**
         * Images décodées : 1920 × 1080 au plus. Le QR code du robot (240 dp, environ 4 cm) doit
         * faire au moins 3,5 pixels par module ; à 1280 × 720, il faudrait approcher le téléphone
         * à une dizaine de centimètres (voir QrDecoderTest).
         */
        private val FRAME_TARGET = Size(1920, 1080)
        private val PREVIEW_TARGET = Size(1920, 1080)

        /**
         * Taille la plus proche de [target] (en surface) sans la dépasser ; de même rapport que
         * [aspectOf] si possible. null si [sizes] est vide.
         */
        internal fun chooseSize(sizes: List<Size>, target: Size, aspectOf: Size?): Size? {
            if (sizes.isEmpty()) return null
            val sameAspect = aspectOf?.let { a -> sizes.filter { it.width.toLong() * a.height == it.height.toLong() * a.width } }
                ?.takeIf { it.isNotEmpty() } ?: sizes
            val area = target.width.toLong() * target.height
            val small = sameAspect.filter { it.width.toLong() * it.height <= area }
            return if (small.isNotEmpty()) small.maxByOrNull { it.width.toLong() * it.height }
            else sameAspect.minByOrNull { it.width.toLong() * it.height }
        }
    }
}
