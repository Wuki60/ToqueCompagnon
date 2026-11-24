package app.toque.companion.update

import app.toque.core.i18n.tr
import app.toque.core.update.Release

/** Où en est la mise à jour, pour la section « Mises à jour » de l'onglet Robot. */
enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }

data class UpdateView(
    /** Version installée : « 0.5.0 ». */
    val installed: String = "",
    val phase: UpdatePhase = UpdatePhase.IDLE,
    /** Version proposée (AVAILABLE, DOWNLOADING, INSTALLING). */
    val version: String = "",
    val notes: String = "",
    /** Téléchargement, de 0 à 100. */
    val percent: Int = 0,
    /** Message d'erreur lisible (ERROR). */
    val error: String = "",
    /** « Vérifier au démarrage » : la vérification à l'ouverture de l'appli. */
    val autoCheck: Boolean = true,
)

/** Échec lisible (réseau, GitHub, fichier, installateur). */
class UpdateFailure(message: String) : Exception(message)

/** Le travail réel, bloquant (réseau, fichier, installateur d'Android) : voir Updater. */
interface UpdateSource {
    fun latest(): Release
    /** Télécharge et vérifie l'APK ; rend le fichier, que [install] reçoit. */
    fun download(r: Release, progress: (Int) -> Unit): Any
    /** Confie l'APK à l'installateur d'Android (il demande confirmation). */
    fun install(apk: Any)
}

/** Réglage gardé : vérification au démarrage, et la date de la dernière vérification. */
interface UpdatePrefs {
    var autoCheck: Boolean
    var lastCheckMs: Long
}

/**
 * Mises à jour du compagnon depuis GitHub : à l'ouverture de l'appli (si « Vérifier au démarrage »,
 * au plus une fois par [minIntervalMs]) et à la demande dans l'onglet Robot. Une version plus récente
 * trouvée au démarrage est proposée une fois ([offer]) ; rien ne s'installe sans l'accord de
 * l'utilisateur, et Android demande encore confirmation. Kotlin pur : [background] fait le travail
 * bloquant hors du fil principal, [main] revient sur le fil principal.
 */
class UpdateFlow(
    private val installed: String,
    private val source: UpdateSource,
    private val prefs: UpdatePrefs,
    private val background: (() -> Unit) -> Unit,
    private val main: (() -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 30 * 60_000L,
) {
    /** L'état a changé (onglet Robot à redessiner). */
    var changed: () -> Unit = {}
    /** Version trouvée à l'ouverture de l'appli : la proposer. */
    var offer: (UpdateView) -> Unit = {}

    var view = UpdateView(installed = installed, autoCheck = prefs.autoCheck)
        private set
    private var release: Release? = null
    private var offered: String? = null

    private fun set(v: UpdateView) {
        if (v == view) return
        view = v
        changed()
    }

    private val busy get() = view.phase == UpdatePhase.CHECKING || view.phase == UpdatePhase.DOWNLOADING || view.phase == UpdatePhase.INSTALLING

    /** Ouverture de l'appli. */
    fun start() {
        if (!prefs.autoCheck) return
        if (clock() - prefs.lastCheckMs < minIntervalMs && view.phase != UpdatePhase.AVAILABLE) return
        check(manual = false)
    }

    /** « Rechercher une mise à jour » (manual) ou vérification au démarrage. */
    fun check(manual: Boolean = true) {
        if (busy) return
        set(view.copy(phase = UpdatePhase.CHECKING, error = ""))
        background {
            val result = try {
                Result.success(source.latest())
            } catch (e: Exception) {
                Result.failure(e)
            }
            main { checked(result, manual) }
        }
    }

    private fun checked(result: Result<Release>, manual: Boolean) {
        prefs.lastCheckMs = clock()
        val r = result.getOrElse { e ->
            // Au démarrage, un échec ne dérange personne : il se voit seulement dans l'onglet Robot.
            set(view.copy(phase = if (manual) UpdatePhase.ERROR else UpdatePhase.IDLE, error = message(e)))
            return
        }
        if (!CompanionReleases.isNewer(r.version, installed)) {
            release = null
            set(view.copy(phase = UpdatePhase.UP_TO_DATE, version = r.version, notes = "", error = ""))
            return
        }
        if (r.apkUrl == null) {
            release = null
            set(view.copy(phase = if (manual) UpdatePhase.ERROR else UpdatePhase.IDLE, version = r.version,
                error = tr("La version %s ne contient pas d'appli à installer.", r.version)))
            return
        }
        release = r
        set(view.copy(phase = UpdatePhase.AVAILABLE, version = r.version, notes = r.notes, error = ""))
        if (!manual && offered != r.version) {
            offered = r.version
            offer(view)
        }
    }

    /** « Installer » : téléchargement, vérification, puis l'installateur d'Android. */
    fun install() {
        val r = release ?: return
        if (busy) return
        set(view.copy(phase = UpdatePhase.DOWNLOADING, percent = 0, error = ""))
        background {
            try {
                val apk = source.download(r) { pct -> main { if (view.phase == UpdatePhase.DOWNLOADING) set(view.copy(percent = pct)) } }
                main { set(view.copy(phase = UpdatePhase.INSTALLING, percent = 100)) }
                source.install(apk)
            } catch (e: Exception) {
                main { set(view.copy(phase = UpdatePhase.ERROR, error = message(e))) }
            }
        }
    }

    /** L'installateur d'Android a refusé ou l'utilisateur a annulé : on peut réessayer. */
    fun installFailed(message: String) {
        set(view.copy(phase = if (release != null) UpdatePhase.ERROR else view.phase, error = message))
    }

    fun setAutoCheck(on: Boolean) {
        prefs.autoCheck = on
        set(view.copy(autoCheck = on))
    }

    /** Réessayer après une erreur : l'installation si une version est connue, sinon la vérification. */
    fun retry() {
        if (busy) return
        if (release != null) {
            set(view.copy(phase = UpdatePhase.AVAILABLE, error = ""))
            install()
        } else check(manual = true)
    }

    private fun message(e: Throwable): String =
        (e as? UpdateFailure)?.message ?: tr("Pas de connexion à GitHub. Vérifiez la connexion à Internet.")
}
