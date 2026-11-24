package app.toque.companion.ui

/**
 * Ce que les écrans « En direct » peuvent demander au robot. Les écrans appellent ces méthodes
 * et ne font rien d'autre : c'est l'implémentation (le client du robot) qui envoie la demande,
 * et le robot qui décide, dans ses limites de sécurité. Le nouvel état revient ensuite par
 * `render(…)`. Toutes les méthodes ne font rien par défaut.
 */
interface LiveActions {
    fun pause() {}
    fun resume() {}
    fun stop() {}

    /** Recette guidée : prolonge l'étape de cuisson en cours d'une minute. */
    fun addMinute() {}

    /** Remet la balance à zéro. */
    fun tare() {}

    /** Cuisson manuelle : durée totale, en secondes (+ ou −). */
    fun adjustTime(deltaSeconds: Int) {}

    /** Cuisson manuelle : température, d'un cran (+1 ou −1). */
    fun adjustTemp(delta: Int) {}

    /** Cuisson manuelle : vitesse, d'un cran (+1 ou −1). */
    fun adjustSpeed(delta: Int) {}

    /** Cuisson manuelle : sens de rotation. */
    fun setReverse(reverse: Boolean) {}

    /** Cuisson manuelle : la durée d'un cran (+1 ou −1) ; en cours, une minute, en préparation, les crans du robot. */
    fun stepDuration(dir: Int) {}

    /** Cuisson manuelle en préparation : l'accessoire monté (fouet ou lame). */
    fun setAccessory(whisk: Boolean) {}

    /** Robot au repos : préparer une cuisson manuelle (écran Manuel en préparation), ou y renoncer. */
    fun openManual() {}
    fun closeManual() {}

    /** Lance la cuisson manuelle préparée ; le robot décide. */
    fun startManual() {}

    /**
     * « OK » sur un programme terminé ou arrêté encore affiché sur le robot : le ferme, comme le
     * bouton OK du robot (rien d'autre ne change sur la machine).
     */
    fun clearEnded() {}

    /**
     * « Arrêter la recette » confirmé (Étapes, Recette en cours) : comme le bouton du robot, la cuisson
     * en cours s'arrête et la recette n'est plus proposée sur son accueil. Le robot décide.
     */
    fun stopRecipe() {}

    /** Robot injoignable : réessayer tout de suite de le joindre, sans attendre la pause en cours. */
    fun retry() {}

    /** Recette envoyée qui attend sur le robot : l'ouvrir, ou la retirer du robot. */
    fun openSentRecipe() {}
    fun withdrawSentRecipe() {}
}
