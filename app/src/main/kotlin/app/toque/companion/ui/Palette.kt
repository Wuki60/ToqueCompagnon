package app.toque.companion.ui

/**
 * Couleurs de base d'un thème du téléphone : celles du robot (app/…/ui/Ui.kt, `setTheme`), plus les
 * teintes propres au téléphone. Pur Kotlin (PaletteTest). [Ui.setLook] choisit le thème et l'accent.
 */
data class Palette(
    val light: Boolean,
    val bg: Int,
    val surface: Int,
    val surface2: Int,
    val surface3: Int,
    val stroke: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    /** Texte secondaire atténué (détail d'une étape faite, bouton inactif) : propre au téléphone. */
    val textMuted: Int,
    /** Couleurs d'état : claires sur le sombre, foncées sur le clair (lisibles en texte sur les surfaces). */
    val cool: Int,
    val ok: Int,
    val warn: Int,
    val danger: Int,
    /** Texte posé sur un fond [cool] ou [warn] (bouton, pastille pleine). */
    val onCool: Int,
    val onWarn: Int,
    val scrim: Int,
    /** Couleur de l'onde d'un appui. */
    val ripple: Int,
    /** Initiales d'une vignette sans photo, sur [placeholders]. */
    val initials: Int,
    /** Dégradés des vignettes sans photo (mêmes que le robot). */
    val placeholders: List<Int>,
) {
    /**
     * Fond discret de l'avertissement (pastilles d'attente, de pause) : [warn] à 16 % sur le fond sombre,
     * 7 % sur le clair (à 16 %, le texte [warn] posé dessus n'y faisait que 4:1 ; le contour la détache du fond).
     */
    val warnSoft: Int get() = Accent.blend(bg, warn, if (light) WARN_SOFT_PART_LIGHT else WARN_SOFT_PART)
    /** Contour d'une carte en pause : [warn] à 45 % sur la surface des cartes. */
    val warnLine: Int get() = Accent.blend(surface, warn, WARN_LINE_PART)

    companion object {
        // Parts qui redonnent au pixel près les teintes de la maquette sur le sombre (0xFF312F29, 0xFF7D673C).
        const val WARN_SOFT_PART = 0.16
        const val WARN_SOFT_PART_LIGHT = 0.07
        const val WARN_LINE_PART = 0.45

        val DARK = Palette(
            light = false,
            bg = 0xFF0A1322.toInt(), surface = 0xFF131D2E.toInt(), surface2 = 0xFF1A2639.toInt(), surface3 = 0xFF223048.toInt(),
            stroke = 0xFF2A3A55.toInt(),
            text = 0xFFF3F5F9.toInt(), text2 = 0xFF9CA8BC.toInt(), text3 = 0xFF66738A.toInt(), textMuted = 0xFF8492A8.toInt(),
            cool = 0xFF5CC8FF.toInt(), ok = 0xFF3DDC97.toInt(), warn = 0xFFFFC14D.toInt(), danger = 0xFFFF5A5F.toInt(),
            onCool = 0xFF04131C.toInt(), onWarn = 0xFF1A1405.toInt(),
            scrim = 0xCC000000.toInt(), ripple = 0x33FFFFFF, initials = 0x8CFFFFFF.toInt(),
            placeholders = listOf(0xFF3A2416.toInt(), 0xFF16302A.toInt(), 0xFF2B2140.toInt(), 0xFF3B1E26.toInt(),
                0xFF1C2B3E.toInt(), 0xFF35301A.toInt(), 0xFF1E3320.toInt(), 0xFF3A2A1C.toInt()),
        )

        /**
         * Le thème clair du robot. Deux écarts, pour la lisibilité : [text3] plus foncé que celui du robot
         * (#8B95A8 : 2,6 à 3,0:1 sur les surfaces claires ; #737D90 : 3,6 à 4,2:1, comme le sombre), et
         * des initiales foncées sur les vignettes claires (le robot les laisse blanches).
         */
        val LIGHT = Palette(
            light = true,
            bg = 0xFFF3F5F8.toInt(), surface = 0xFFFFFFFF.toInt(), surface2 = 0xFFEBEEF3.toInt(), surface3 = 0xFFDEE3EB.toInt(),
            stroke = 0xFFC5CDD9.toInt(),
            text = 0xFF1A2332.toInt(), text2 = 0xFF5A6578.toInt(), text3 = 0xFF737D90.toInt(), textMuted = 0xFF667085.toInt(),
            cool = 0xFF0B6FA8.toInt(), ok = 0xFF10794D.toInt(), warn = 0xFF9A5B00.toInt(), danger = 0xFFC62828.toInt(),
            onCool = 0xFFFFFFFF.toInt(), onWarn = 0xFFFFFFFF.toInt(),
            scrim = 0x99000000.toInt(), ripple = 0x22000000, initials = 0x8C1A2332.toInt(),
            placeholders = listOf(0xFFE8D5C4.toInt(), 0xFFC5DDD4.toInt(), 0xFFD4CDE4.toInt(), 0xFFE4CCD2.toInt(),
                0xFFC8D5E4.toInt(), 0xFFE0DBC4.toInt(), 0xFFC9DEC9.toInt(), 0xFFE4D4C4.toInt()),
        )

        /** « light » : le clair ; tout le reste (« dark », inconnu, vide) : le sombre. */
        fun of(theme: String): Palette = if (theme == "light") LIGHT else DARK
    }
}
