package app.toque.companion.ui.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.toque.companion.scan.QrScannerView
import app.toque.companion.ui.BackBar
import app.toque.companion.ui.Icon
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.PairingActions
import app.toque.companion.ui.Screen
import app.toque.companion.ui.StepMarker
import app.toque.companion.ui.StepStatus
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.core.i18n.tr

/** Accès à l'appareil photo, tel que l'appli le connaît. */
enum class CameraAccess {
    /** Permission CAMERA pas encore accordée : bouton « Autoriser l'appareil photo ». */
    ASK,
    /** Permission refusée sans nouvelle demande possible : bouton vers les réglages (même rappel). */
    DENIED,
    /** Permission accordée : l'aperçu et la lecture tournent tant que l'écran est affiché. */
    READY,
    /** Pas d'appareil photo utilisable : seul le code reste. */
    UNAVAILABLE,
}

/** Échecs de l'appairage, montrés en clair. */
enum class PairingError {
    /** Le robot a refusé le code (mauvais chiffres). */
    CODE_REFUSED,
    /** Le code ou le QR code a expiré (2 minutes). */
    CODE_EXPIRED,
    /** Trop d'essais : le robot a annulé ce code (`code_dead`). */
    TOO_MANY_TRIES,
    /** Aucun robot avec l'appairage ouvert sur ce Wi-Fi. */
    ROBOT_NOT_FOUND,
    /** Plusieurs robots ont l'appairage ouvert. */
    SEVERAL_ROBOTS,
    /** Le QR code lu n'est pas un QR code d'appairage Toque. */
    NOT_A_TOQUE_CODE,
    /** Le robot ne répond pas (adresse du QR code injoignable, Wi-Fi différent). */
    UNREACHABLE,
    /** Le robot a déjà son maximum de téléphones (`too_many_phones`). */
    TOO_MANY_PHONES,
}

/**
 * Appairage du téléphone (plein écran, sans onglets ; maquette « Sur le téléphone : appairer par
 * QR code »). Deux modes : le lecteur de QR code, et la saisie du code à 6 chiffres (« Saisir le
 * code… », ou [showCodeEntry]). Les chiffres saisis restent dans l'écran ; ils partent par
 * [PairingActions.codeSubmitted].
 */
data class AppairageState(
    val camera: CameraAccess = CameraAccess.READY,
    /** Appairage en cours (QR code lu ou code envoyé) : rien ne se touche, message d'attente. */
    val busy: Boolean = false,
    val error: PairingError? = null,
    /** Montre le champ « Adresse du robot » sous le code (robot introuvable par la recherche sur le Wi-Fi). */
    val askAddress: Boolean = false,
    /** Pourquoi l'appairage est à refaire (« Ce téléphone a été retiré sur le robot… »), montré sans [error]. */
    val message: String? = null,
)

class AppairageScreen(c: Context, private val actions: PairingActions, private val nav: Navigator) : Screen<AppairageState>(c) {
    override val tab = Tab.ROBOT
    private val pages = FrameLayout(c)
    private lateinit var scanPage: View
    private lateinit var codePage: View

    // Lecteur.
    private val scanner = QrScannerView(c, { onQr(it) }, { onCameraError(it) })
    private val corners = CornerMarks(c)
    private val cameraIcon = Icon(c, Icon.Kind.CAMERA, Ui.CAMERA_TEXT)
    private val cameraText = Ui.text(c, "", 14f, Ui.CAMERA_TEXT, lineHeight = 19f).apply { gravity = Gravity.CENTER; maxWidth = Ui.dp(220) }
    private lateinit var cameraButton: Ui.Button
    private val hint = Ui.vertical(c, Gravity.CENTER)
    private val busyBox = Ui.vertical(c, Gravity.CENTER)
    private val scanError = Ui.horizontal(c, Gravity.TOP)
    private val scanErrorText = Ui.text(c, "", 14f, Ui.TEXT, lineHeight = 20f)
    private lateinit var enterCode: Ui.Button

    // Saisie du code.
    private val digits = EditText(c)
    private val cells = ArrayList<TextView>()
    private val addressBox = Ui.vertical(c)
    private val address = EditText(c)
    private val codeError = Ui.text(c, "", 14f, Ui.WARN, lineHeight = 20f)
    private val codeBusy = Ui.horizontal(c)
    private lateinit var submit: Ui.Button

    private var codeMode = false
    private var visible = false
    /** L'appareil photo a échoué depuis le dernier changement de [AppairageState.camera]. */
    private var cameraBroken = false
    private var binding = false

    override fun build(): View {
        scanPage = buildScan()
        codePage = buildCode()
        pages.addView(scanPage, Ui.flp())
        pages.addView(codePage, Ui.flp())
        setMode(false)
        return pages
    }

    private fun buildScan(): View {
        val root = Ui.vertical(c)
        val column = Ui.vertical(c)
        val head = Ui.vertical(c)
        Ui.pad(head, 20, 22, 20, 0)
        val brand = Ui.horizontal(c)
        brand.addView(Icon(c, Icon.Kind.POT, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)).apply { rightMargin = Ui.dp(10) })
        brand.addView(Ui.text(c, "Toque Compagnon", 15f, Ui.TEXT, Ui.MEDIUM))
        head.addView(brand)
        head.addView(Ui.text(c, tr("Appairer ce téléphone"), 26f, Ui.TEXT, Ui.MEDIUM, lineHeight = 32f).apply { letterSpacing = -0.2f / 26f },
            Ui.lp(t = 22))
        head.addView(Ui.text(c, tr("À faire une seule fois, devant le robot. Ensuite, ce téléphone pourra le suivre et le piloter."),
            15f, Ui.TEXT2, lineHeight = 21f), Ui.lp(t = 8))
        column.addView(head)

        // Zone de l'appareil photo : aperçu, repères d'angle, messages (permission, attente).
        val camera = FrameLayout(c)
        // L'image de la caméra et son cadre restent sombres, quel que soit le thème.
        camera.background = Ui.round(Ui.CAMERA_BG, 28f)
        camera.tag = Ui.FIXED
        camera.clipToOutline = true
        Ui.pad(hint, 24, 24, 24, 24)
        hint.addView(cameraIcon, LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)))
        hint.addView(cameraText, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        cameraButton = Ui.button(c, tr("Autoriser l'appareil photo"), Ui.Style.PRIMARY, height = 44, textSize = 14f) {
            actions.cameraPermissionWanted()
        }
        hint.addView(cameraButton, Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
        camera.addView(hint, Ui.flp())
        scanner.contentDescription = tr("Aperçu de l'appareil photo")
        camera.addView(scanner, Ui.flp())
        camera.addView(corners, Ui.flp().apply { setMargins(Ui.dp(18), Ui.dp(18), Ui.dp(18), Ui.dp(18)) })
        busyBox.background = Ui.round(0xCC000000.toInt(), 28f)
        busyBox.addView(ProgressBar(c).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        }, LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)))
        busyBox.addView(Ui.text(c, tr("Appairage en cours…"), 15f, Ui.ON_PHOTO, Ui.MEDIUM), Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        camera.addView(busyBox, Ui.flp())
        column.addView(camera, Ui.lp(Ui.MATCH, Ui.dp(310), l = 40, t = 18, r = 40))

        // Erreur, sous l'aperçu.
        scanError.background = Ui.round(Ui.WARN_SOFT, 20f, Ui.WARN_LINE, 1f)
        Ui.pad(scanError, 14, 12, 16, 12)
        scanError.addView(Icon(c, Icon.Kind.WARNING, Ui.WARN), LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)).apply { rightMargin = Ui.dp(10) })
        scanError.addView(scanErrorText, Ui.lp(0, Ui.WRAP, 1f))
        scanError.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        column.addView(scanError, Ui.lp(l = 20, t = 16, r = 20))

        val steps = Ui.card(c)
        Ui.pad(steps, 16, 16, 16, 16)
        steps.addView(numbered(1, tr("Sur le robot : Réglages, Compagnon, puis Appairer un téléphone.")))
        steps.addView(numbered(2, tr("Scannez le QR code qui s'affiche sur son écran.")), Ui.lp(t = 12))
        column.addView(steps, Ui.lp(l = 20, t = 16, r = 20))
        column.addView(Ui.spacer(c, h = 12))
        root.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))

        val bottom = Ui.vertical(c)
        Ui.pad(bottom, 20, 0, 20, 22)
        enterCode = Ui.button(c, tr("Saisir le code à 6 chiffres"), Ui.Style.GHOST, height = 52) { showCodeEntry(true) }
        bottom.addView(enterCode, Ui.lp())
        bottom.addView(Ui.text(c, tr("Sans appairage, un téléphone ne peut ni suivre ni piloter le robot, même sur le même Wi-Fi."),
            13f, Ui.TEXT2, lineHeight = 18f).apply { gravity = Gravity.CENTER }, Ui.lp(t = 12))
        root.addView(bottom, Ui.lp())
        return root
    }

    private fun buildCode(): View {
        val root = Ui.vertical(c)
        val column = Ui.vertical(c)
        val bar = BackBar(c, tr("Revenir au QR code")) { showCodeEntry(false) }
        Ui.set(bar.title, tr("Saisir le code"))
        Ui.show(bar.subtitle, false)
        // Barre fixée en haut ; les explications et les cases défilent dessous.
        root.addView(bar)
        column.addView(Ui.text(c, tr("Sur le robot : Réglages, Compagnon, puis Appairer un téléphone. Le code s'affiche sous le QR code et reste valable 2 minutes."),
            15f, Ui.TEXT2, lineHeight = 21f), Ui.lp(l = 20, t = 10, r = 20))

        // Six cases dessinées ; un champ invisible par-dessus reçoit le clavier numérique.
        val box = FrameLayout(c)
        val row = Ui.horizontal(c, Gravity.CENTER)
        for (i in 0 until 6) {
            val cell = Ui.tabular(Ui.text(c, "", 26f, Ui.TEXT, Ui.MEDIUM)).apply { gravity = Gravity.CENTER }
            cell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            cells += cell
            row.addView(cell, LinearLayout.LayoutParams(0, Ui.dp(60), 1f).apply { leftMargin = Ui.dp(if (i == 0) 0 else if (i == 3) 16 else 8) })
        }
        box.addView(row, Ui.flp(Ui.MATCH, Ui.WRAP))
        digits.apply {
            background = null
            setTextColor(Color.TRANSPARENT)
            isCursorVisible = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 1f)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = tr("Code à 6 chiffres")
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { trySubmit(); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    paintCells()
                    if (!binding && s?.length == 6 && state?.askAddress != true) trySubmit()
                }
            })
        }
        box.addView(digits, Ui.flp(Ui.MATCH, Ui.dp(60)))
        box.setOnClickListener { focusDigits() }
        column.addView(box, Ui.lp(l = 20, t = 22, r = 20))

        // Adresse du robot, seulement à la demande.
        addressBox.addView(Ui.overline(c, tr("Adresse du robot")))
        address.apply {
            background = Ui.round(Ui.SURFACE, 24f, Ui.STROKE, 1f)
            setPadding(Ui.dp(16), 0, Ui.dp(16), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            hint = "192.168.0.12"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // Après setSingleLine, qui remplace la hauteur minimale par un nombre de lignes.
            minHeight = Ui.dp(48)
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = tr("Adresse du robot")
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { trySubmit(); true } else false }
        }
        addressBox.addView(address, Ui.lp(t = 8))
        addressBox.addView(Ui.text(c, tr("Elle s'affiche sur l'écran d'appairage du robot, sous le code."), 13f, Ui.TEXT2, lineHeight = 18f), Ui.lp(t = 6))
        column.addView(addressBox, Ui.lp(l = 20, t = 22, r = 20))

        codeError.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        column.addView(codeError, Ui.lp(l = 20, t = 16, r = 20))
        codeBusy.addView(ProgressBar(c).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT_INK)
        }, LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)).apply { rightMargin = Ui.dp(10) })
        codeBusy.addView(Ui.text(c, tr("Appairage en cours…"), 15f, Ui.TEXT2))
        column.addView(codeBusy, Ui.lp(l = 20, t = 16, r = 20))
        column.addView(Ui.spacer(c, h = 12))
        root.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))

        val bottom = Ui.vertical(c)
        Ui.pad(bottom, 20, 10, 20, 22)
        submit = Ui.button(c, tr("Appairer"), Ui.Style.PRIMARY, height = 56, textSize = 17f) { trySubmit() }
        bottom.addView(submit, Ui.lp())
        root.addView(bottom, Ui.lp())
        return root
    }

    private fun numbered(n: Int, text: String): View {
        val row = Ui.horizontal(c, Gravity.TOP)
        row.addView(StepMarker(c).apply { set(StepStatus.TODO, n) }, LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)).apply { rightMargin = Ui.dp(12) })
        row.addView(Ui.text(c, text, 15f, lineHeight = 21f), Ui.lp(0, Ui.WRAP, 1f))
        return row
    }

    /** Ouvre (true) ou ferme la saisie du code ; le lecteur s'arrête pendant la saisie. */
    fun showCodeEntry(open: Boolean) {
        if (open == codeMode) return
        setMode(open)
    }

    private fun setMode(code: Boolean) {
        codeMode = code
        Ui.show(scanPage, !code)
        Ui.show(codePage, code)
        val imm = c.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (code) {
            (codePage as? LinearLayout)?.let { (it.getChildAt(1) as? ScrollView)?.scrollTo(0, 0) }
            focusDigits()
        } else imm?.hideSoftInputFromWindow(digits.windowToken, 0)
        paintCells()
        state?.let { refresh(it) }
        updateScanner()
    }

    private fun focusDigits() {
        digits.requestFocus()
        digits.post {
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(digits, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun paintCells() {
        val text = digits.text?.toString().orEmpty()
        val busy = state?.busy == true
        cells.forEachIndexed { i, cell ->
            Ui.set(cell, text.getOrNull(i)?.toString().orEmpty())
            val next = i == text.length && !busy
            cell.background = Ui.round(Ui.SURFACE, 14f, if (next) Ui.ACCENT_INK else Ui.STROKE, if (next) 2f else 1f)
        }
    }

    private fun trySubmit() {
        val s = state ?: return
        val code = digits.text?.toString().orEmpty()
        if (s.busy || code.length != 6) return
        val addr = address.text?.toString()?.trim()
        if (s.askAddress && addr.isNullOrEmpty()) {
            address.requestFocus()
            return
        }
        actions.codeSubmitted(code, if (s.askAddress) addr else null)
    }

    private fun onQr(text: String) {
        val s = state ?: return
        if (s.busy || codeMode) return
        actions.qrRead(text)
    }

    private fun onCameraError(reason: String) {
        cameraBroken = true
        state?.let { refresh(it) }
        actions.cameraFailed(reason)
    }

    override fun bind(s: AppairageState, old: AppairageState?) {
        if (old != null && old.camera != s.camera) cameraBroken = false
        // Code expiré ou annulé : les chiffres ne servent plus.
        if (s.error != old?.error && (s.error == PairingError.CODE_EXPIRED || s.error == PairingError.TOO_MANY_TRIES)) {
            binding = true
            digits.setText("")
            binding = false
        }
        refresh(s)
        updateScanner()
    }

    private fun refresh(s: AppairageState) {
        val camera = if (cameraBroken && s.camera == CameraAccess.READY) CameraAccess.UNAVAILABLE else s.camera
        val live = camera == CameraAccess.READY
        Ui.show(scanner, live)
        Ui.show(corners, live)
        Ui.show(cameraButton, camera == CameraAccess.ASK || camera == CameraAccess.DENIED)
        Ui.set(cameraButton.label, if (camera == CameraAccess.DENIED) tr("Ouvrir les réglages") else tr("Autoriser l'appareil photo"))
        Ui.set(cameraText, when (camera) {
            CameraAccess.READY -> tr("Visez le QR code affiché sur le robot")
            CameraAccess.ASK -> tr("Toque Compagnon a besoin de l'appareil photo pour lire le QR code du robot.")
            CameraAccess.DENIED -> tr("L'appareil photo est refusé. Autorisez-le dans les réglages du téléphone, ou saisissez le code.")
            CameraAccess.UNAVAILABLE -> tr("Appareil photo indisponible. Saisissez le code à 6 chiffres affiché sur le robot.")
        })
        Ui.show(busyBox, s.busy)
        Ui.show(hint, !s.busy)
        Ui.setButtonEnabled(enterCode, !s.busy)
        Ui.setButtonEnabled(cameraButton, !s.busy)

        val message = s.error?.let { message(it) } ?: s.message
        Ui.show(scanError, message != null && !codeMode)
        Ui.show(codeError, message != null && codeMode && !s.busy)
        if (message != null) {
            Ui.set(scanErrorText, message)
            Ui.set(codeError, message)
        }
        Ui.show(addressBox, s.askAddress)
        Ui.show(codeBusy, s.busy && codeMode)
        Ui.setButtonEnabled(submit, !s.busy)
        digits.isEnabled = !s.busy
        address.isEnabled = !s.busy
        paintCells()
    }

    private fun message(e: PairingError): String = when (e) {
        PairingError.CODE_REFUSED -> tr("Code refusé. Vérifiez les 6 chiffres affichés sur le robot.")
        PairingError.CODE_EXPIRED -> tr("Ce code a expiré. Sur le robot, touchez « Nouveau code », puis recommencez.")
        PairingError.TOO_MANY_TRIES -> tr("Trop d'essais : ce code ne marche plus. Sur le robot, touchez « Nouveau code ».")
        PairingError.ROBOT_NOT_FOUND -> tr("Aucun robot trouvé sur ce Wi-Fi. Vérifiez que le téléphone est sur le Wi-Fi de la maison et que l'appairage est ouvert sur le robot.")
        PairingError.SEVERAL_ROBOTS -> tr("Plusieurs robots attendent un téléphone sur ce Wi-Fi. Saisissez l'adresse affichée sur le vôtre, ou scannez son QR code.")
        PairingError.NOT_A_TOQUE_CODE -> tr("Ce QR code n'est pas celui de Toque. Scannez celui qui s'affiche sur le robot.")
        PairingError.UNREACHABLE -> tr("Le robot ne répond pas. Vérifiez que le téléphone est sur le même Wi-Fi que le robot.")
        PairingError.TOO_MANY_PHONES -> tr("Le robot a déjà son maximum de téléphones : retirez-en un sur le robot, puis recommencez.")
    }

    /** Le lecteur tourne seulement si l'écran est affiché, en mode QR code, permission accordée, sans attente. */
    private fun updateScanner() {
        val s = state
        val run = visible && !codeMode && s != null && s.camera == CameraAccess.READY && !cameraBroken && !s.busy
        if (run) scanner.start() else scanner.stop()
    }

    override fun onShow() {
        visible = true
        updateScanner()
    }

    override fun onHide() {
        visible = false
        updateScanner()
        (c.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(digits.windowToken, 0)
    }

    /** Code en cours de saisie : l'écran garde ses couleurs tant qu'il est ouvert. */
    override val keepsWork: Boolean get() = codeMode

    override fun onBack(): Boolean {
        if (!codeMode) return false
        showCodeEntry(false)
        return true
    }

    /** Repères d'angle orange du cadre de visée (maquette : traits de 4 dp, arrondis de 16 dp, branches de 44 dp). */
    private class CornerMarks(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Ui.ACCENT
        }
        private val path = Path()
        private val r = RectF()

        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val sw = Ui.dpf(4f)
            paint.strokeWidth = sw
            val side = minOf(width, height).toFloat() - sw
            val l = (width - side) / 2f
            val t = (height - side) / 2f
            val rad = Ui.dpf(16f)
            val arm = Ui.dpf(44f)
            path.reset()
            // Haut gauche, haut droit, bas droit, bas gauche.
            path.moveTo(l, t + arm); path.lineTo(l, t + rad); r.set(l, t, l + 2 * rad, t + 2 * rad); path.arcTo(r, 180f, 90f); path.lineTo(l + arm, t)
            path.moveTo(l + side - arm, t); path.lineTo(l + side - rad, t); r.set(l + side - 2 * rad, t, l + side, t + 2 * rad); path.arcTo(r, 270f, 90f); path.lineTo(l + side, t + arm)
            path.moveTo(l + side, t + side - arm); path.lineTo(l + side, t + side - rad); r.set(l + side - 2 * rad, t + side - 2 * rad, l + side, t + side); path.arcTo(r, 0f, 90f); path.lineTo(l + side - arm, t + side)
            path.moveTo(l + arm, t + side); path.lineTo(l + rad, t + side); r.set(l, t + side - 2 * rad, l + 2 * rad, t + side); path.arcTo(r, 90f, 90f); path.lineTo(l, t + side - arm)
            canvas.drawPath(path, paint)
        }
    }
}
