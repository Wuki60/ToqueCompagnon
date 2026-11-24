package app.toque.companion.flows

import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.PairingActions
import app.toque.companion.ui.screens.AppairageState
import app.toque.companion.ui.screens.CameraAccess
import app.toque.companion.ui.screens.PairingError
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.PairingPayload

/**
 * L'écran Appairage branché sur la liaison. Un QR code lu va à [RobotLink.pairWithQr] ; un code
 * tapé à [RobotLink.pairWithCode] (recherche sur le Wi-Fi). Aucun robot ou plusieurs : l'écran
 * demande l'adresse affichée sur le robot, puis [RobotLink.pairWithCodeAt]. Un code n'est jamais
 * essayé sur plus d'un robot. Réussi : la liaison passe à « connexion » et l'activité quitte
 * l'écran d'elle-même. Fil principal.
 */
class PairingFlow(
    private val link: RobotLink,
    private val render: (AppairageState) -> Unit,
    /** « Autoriser l'appareil photo » (ou ouvrir les réglages si [CameraAccess.DENIED]). */
    private val onCameraWanted: (CameraAccess) -> Unit = {},
) : PairingActions {
    var state = AppairageState()
        private set
    /** Dernier texte de QR code refusé : l'appareil photo le relit en boucle, on ne le renvoie pas. */
    private var lastBadQr: String? = null

    private fun set(s: AppairageState) {
        state = s
        render(s)
    }

    /**
     * L'écran repart propre (affiché de nouveau, après « Oublier »…). [camera] : l'accès connu ;
     * [message] : pourquoi l'appairage est à refaire (téléphone retiré sur le robot…), ou null.
     */
    fun reset(camera: CameraAccess = state.camera, message: String? = null) {
        lastBadQr = null
        set(AppairageState(camera = camera, message = message))
    }

    fun setCamera(access: CameraAccess) {
        if (access != state.camera) set(state.copy(camera = access))
    }

    override fun cameraPermissionWanted() = onCameraWanted(state.camera)

    override fun cameraFailed(reason: String) {
        // L'écran bascule de lui-même sur « appareil photo indisponible » et propose le code.
    }

    override fun qrRead(text: String) {
        if (state.busy || text == lastBadQr) return
        if (PairingPayload.parse(text) == null) {
            lastBadQr = text
            set(state.copy(error = PairingError.NOT_A_TOQUE_CODE))
            return
        }
        set(state.copy(busy = true, error = null, message = null))
        link.pairWithQr(text) { r ->
            if (r !is PairResult.Paired) lastBadQr = text
            done(r, qr = true)
        }
    }

    override fun codeSubmitted(code: String, address: String?) {
        if (state.busy) return
        set(state.copy(busy = true, error = null, message = null))
        if (address != null) {
            val (host, port) = parseAddress(address) ?: run {
                set(state.copy(busy = false, error = PairingError.ROBOT_NOT_FOUND, askAddress = true))
                return
            }
            link.pairWithCodeAt(host, code, { done(it, qr = false) }, port)
        } else link.pairWithCode(code) { done(it, qr = false) }
    }

    private fun done(r: PairResult, qr: Boolean) {
        val s = state.copy(busy = false)
        set(when (r) {
            is PairResult.Paired -> s.copy(error = null, askAddress = false)
            is PairResult.InvalidQr -> s.copy(error = PairingError.NOT_A_TOQUE_CODE)
            // Sans réponse unique sur le Wi-Fi : l'adresse affichée sur le robot, sous le code.
            PairResult.NotOnWifi, PairResult.NoRobot -> s.copy(error = PairingError.ROBOT_NOT_FOUND, askAddress = true)
            is PairResult.SeveralRobots -> s.copy(error = PairingError.SEVERAL_ROBOTS, askAddress = true)
            is PairResult.Failed -> s.copy(error = PairingError.UNREACHABLE)
            is PairResult.Refused -> s.copy(error = refusal(r.code, qr))
        })
    }

    companion object {
        fun refusal(code: String, qr: Boolean): PairingError = when (code) {
            // Un QR code refusé est un ancien QR code (le secret a changé avec « Nouveau code »).
            "wrong_code" -> if (qr) PairingError.CODE_EXPIRED else PairingError.CODE_REFUSED
            "code_expired", "pairing_closed" -> PairingError.CODE_EXPIRED
            "code_dead" -> PairingError.TOO_MANY_TRIES
            "too_many_phones" -> PairingError.TOO_MANY_PHONES
            else -> PairingError.UNREACHABLE
        }

        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

        /**
         * « 192.168.0.12 » ou « 192.168.0.12:8089 », tel que le robot l'affiche (« Adresse du robot : … ») ;
         * null si ce n'est pas une adresse IPv4.
         */
        fun parseAddress(text: String, defaultPort: Int = CompanionHub.DEFAULT_PORT): Pair<String, Int>? {
            val t = text.trim().removePrefix("http://").trimEnd('/')
            val host = t.substringBefore(':')
            val port = if (':' in t) t.substringAfter(':').toIntOrNull() ?: return null else defaultPort
            if (!IPV4.matches(host) || port !in 1..65535) return null
            return host to port
        }
    }
}
