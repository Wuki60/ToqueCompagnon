package app.toque.companion.ui

import app.toque.core.i18n.tr

/** Textes formatés communs aux écrans (traduits), pour les écrans et pour le code qui fait les états. */
object Fmt {
    /** « 45 s », « 20 min », « 1 min 30 », « 1 h 05 ». */
    fun duration(seconds: Int): String = when {
        seconds < 60 -> tr("%d s", seconds)
        seconds < 3600 && seconds % 60 == 0 -> tr("%d min", seconds / 60)
        seconds < 3600 -> tr("%d min %02d", seconds / 60, seconds % 60)
        else -> tr("%d h %02d", seconds / 3600, (seconds / 60) % 60)
    }

    /** Réglages d'une étape : « 20 min · 100 °C · vitesse 1 » (les valeurs nulles sont omises). */
    fun cooking(seconds: Int?, tempC: Int?, speed: Int?): String =
        listOfNotNull(seconds?.let { duration(it) }, tempC?.let { tr("%d °C", it) }, speed?.let { tr("vitesse %d", it) })
            .joinToString(" · ")

    /** Nom d'une vitesse, comme sur le robot (cuisson manuelle). */
    fun speedName(speed: Int): String = when (speed) {
        0 -> tr("Arrêt")
        1 -> tr("Mijoter")
        2, 3 -> tr("Mélanger")
        in 4..6 -> tr("Hacher")
        else -> tr("Mixer")
    }

    /** Nom d'une consigne de température, comme sur le robot (cuisson manuelle). */
    fun tempName(tempC: Int): String = when {
        tempC <= 0 -> tr("Sans chauffe")
        tempC >= 100 -> tr("Ébullition")
        tempC >= 60 -> tr("Cuisson")
        else -> tr("Tiédir")
    }

    /** État du bol d'après sa température : « froid », « tiède », « encore chaud ». */
    fun bowlNote(tempC: Int): String = when {
        tempC < 40 -> tr("froid")
        tempC < 60 -> tr("tiède")
        else -> tr("encore chaud")
    }

    /** Durée comme sur les cartes du robot : « 01:30 », « 1:05:00 » au-delà d'une heure. */
    fun clock(seconds: Int): String =
        if (seconds >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
        else String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)

    /** Poids : « 372 g ». */
    fun grams(g: Int): String = tr("%d g", g)
}
