package app.toque.companion.alerts

import app.toque.companion.link.LinkPhase
import app.toque.companion.live.LivePresenter
import app.toque.core.companion.JobWire
import app.toque.core.companion.StateWire
import app.toque.core.i18n.tr

/** Boutons de la notification de la cuisson (cuisson manuelle seulement) ; jamais d'arrêt. */
enum class NoticeAction(val action: String) {
    PAUSE("app.toque.companion.action.PAUSE"),
    RESUME("app.toque.companion.action.RESUME"),
    ADD_MINUTE("app.toque.companion.action.ADD_MINUTE");

    companion object {
        fun of(action: String?): NoticeAction? = values().firstOrNull { it.action == action }
    }
}

/**
 * Ce que dit la notification de la cuisson : [title] (la recette ou le programme), [text] (l'étape et
 * l'état), le temps restant si le minuteur avance ([remainingMs], 0 sinon) et les boutons permis.
 */
data class CookingNotice(
    val title: String,
    val text: String,
    val remainingMs: Long = 0,
    val actions: List<NoticeAction> = emptyList(),
)

/**
 * Texte et boutons de la notification de la cuisson, d'après l'état du robot. Kotlin pur.
 *
 * Seulement ce qui réveille le téléphone en arrière-plan (attente « quiet ») : la recette, l'étape,
 * ce que le robot attend, l'état du programme et ses réglages, les permissions. Jamais la
 * température mesurée ni le poids, qui ne réveillent pas le téléphone et seraient vite faux.
 *
 * Boutons : pendant une cuisson manuelle seulement (hors recette guidée), « Pause » ou « Reprendre »
 * et « +1 min », chacun seulement si le robot le permet (`can`) ; une reprise après l'ouverture du
 * couvercle reste sur le robot (`can.resume` la refuse). Jamais d'arrêt : un appui par erreur sur
 * l'écran verrouillé arrêterait la cuisson. [sending] : une demande est en route, pas de bouton.
 */
object CookingNotices {
    private val ACTIVE = setOf("STARTING", "RUNNING", "STOPPING", "PAUSED")

    fun describe(s: StateWire?, phase: LinkPhase, sending: Boolean = false): CookingNotice {
        val follow = tr("Toque Compagnon suit le robot")
        if (phase == LinkPhase.UNREACHABLE) return CookingNotice(follow, tr("Le robot ne répond pas"))
        if (s == null) return CookingNotice(follow, tr("Connexion au robot"))
        val job = s.job?.takeIf { it.state in ACTIVE }
        val remaining = if (job != null && job.counting) job.remainingMs.coerceAtLeast(0) else 0L
        val session = s.session
        if (session != null) {
            val step = if (session.stepCount > 0) tr("Étape %d sur %d", session.current + 1, session.stepCount) else null
            val status = if (job != null) jobStatus(job, inRecipe = true) else waiting(s)
            return CookingNotice(session.title, listOfNotNull(step, status).joinToString(" · ").ifEmpty { tr("Recette en cours") },
                remaining)
        }
        if (job != null) {
            val manual = LivePresenter.isManual(job)
            return CookingNotice(job.label.ifEmpty { job.kindLabel }, jobStatus(job, inRecipe = false), remaining,
                if (manual && !sending) actions(s, job) else emptyList())
        }
        val w = s.waiting
        if (w != null) return CookingNotice(w.title, tr("Recette reçue : lancez-la sur le robot"))
        return CookingNotice(follow, tr("Rien en cours"))
    }

    /** L'état du programme : en pause (raison du robot), arrêt en cours, ou ce qui tourne. */
    private fun jobStatus(job: JobWire, inRecipe: Boolean): String = when (job.state) {
        "PAUSED" -> job.pause?.label?.ifEmpty { null } ?: tr("En pause")
        "STOPPING" -> tr("Arrêt en cours")
        "STARTING" -> tr("Démarrage")
        else -> when {
            // Cuisson manuelle : ses réglages (consigne, vitesse), jamais la température mesurée.
            LivePresenter.isManual(job) && !inRecipe -> listOf(
                if (job.tempLevel > 0) tr("%d °C", job.tempC) else tr("sans chauffe"),
                if (job.speed == 0) tr("sans mélange") else tr("vitesse %d", job.speed),
            ).joinToString(" · ")
            !inRecipe -> tr("En cours")
            job.kind == "MANUAL" -> tr("Cuisson")
            else -> job.kindLabel.ifEmpty { job.label }
        }
    }

    /** Ce que le robot attend du cuisinier entre deux programmes. */
    private fun waiting(s: StateWire): String? {
        val session = s.session ?: return null
        return when (session.waitingFor) {
            "weigh" -> session.steps.getOrNull(session.current)?.weigh?.label?.takeIf { it.isNotBlank() }
                ?.let { tr("Attend : pesée (%s)", it) } ?: tr("Attend : pesée")
            "hand" -> tr("Attend : étape à la main")
            "launch" -> tr("Attend : lancez l'étape sur le robot")
            "hot_blend" -> tr("Attend : confirmez le mixage sur le robot")
            "finish" -> tr("Attend : terminez sur le robot")
            "resume" -> tr("En pause")
            else -> null
        }
    }

    private fun actions(s: StateWire, job: JobWire): List<NoticeAction> {
        val out = ArrayList<NoticeAction>()
        when (job.state) {
            "PAUSED" -> if (s.can.resume.allowed) out += NoticeAction.RESUME
            "RUNNING", "STARTING" -> if (s.can.pause.allowed) out += NoticeAction.PAUSE
        }
        if (job.state != "STOPPING" && s.can.adjust.allowed) out += NoticeAction.ADD_MINUTE
        return out
    }
}
