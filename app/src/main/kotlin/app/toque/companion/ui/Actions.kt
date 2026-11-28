package app.toque.companion.ui

import android.graphics.Bitmap
import app.toque.companion.ui.screens.AlertKind
import app.toque.companion.ui.screens.JournalFilter
import app.toque.core.recipes.RecipeChip

/*
 * Ce que les écrans des onglets Recettes, Journal et Robot, et l'écran d'appairage, peuvent
 * demander. Comme LiveActions : les écrans appellent, l'implémentation (le client du robot, les
 * préférences du téléphone) agit, et le nouvel état revient par `render(…)`. Toutes les méthodes
 * ne font rien par défaut. Elles sont appelées sur le fil principal et ne doivent pas bloquer.
 */

/** Catalogue de recettes (écrans Recettes, Fiche). */
interface RecipeActions {
    /** Le texte de recherche a changé (à chaque touche : à temporiser côté implémentation). */
    fun search(query: String) {}

    /** Pastille choisie (Toutes, Récentes… ou une catégorie du robot). */
    fun filter(filter: RecipeChip) {}

    /**
     * La grille approche de la fin des recettes chargées alors que [RecettesState.hasMore] :
     * charger la page suivante. Appelé une fois par nombre de recettes chargées.
     */
    fun loadMore() {}

    /** Une carte touchée : préparer l'état de la fiche, puis `nav.open(Route.FICHE)`. */
    fun openRecipe(id: String) {}

    /** « Envoyer au robot » sur la fiche. En cas de succès : état d'Envoyée, puis `nav.open(Route.ENVOYEE)`. */
    fun sendToRobot(id: String) {}

    /** « Réessayer » après une erreur de chargement du catalogue ou d'une fiche. */
    fun retry() {}

    /** Nombre de personnes de la fiche [id] (− / +, « Revenir à… ») ; le robot l'enregistre et renvoie la fiche. */
    fun setServings(id: String, people: Int) {}

    /** Étoile touchée (fiche, carte) quand le robot le permet : il enregistre le favori, l'étoile change tout de suite. */
    fun setFavorite(id: String, favorite: Boolean) {}

    /** « Partager les ingrédients » de la fiche [id] : en texte, vers l'appli choisie. */
    fun shareIngredients(id: String) {}

    /** « Ajouter à la liste » de la fiche [id] : ses ingrédients, aux quantités affichées, dans l'onglet Courses. */
    fun addToList(id: String) {}

    /** « Écrire une recette » (sous la pastille « Mes recettes ») quand le robot le permet. */
    fun writeRecipe() {}

    /** « Modifier » sur la fiche d'une de ses recettes. */
    fun editRecipe(id: String) {}

    /** « Ma version » confirmé sur la fiche d'une recette Lidl : le robot crée la copie, puis l'éditeur l'ouvre. */
    fun myVersion(id: String) {}

    /**
     * Étape [index] touchée sur la fiche d'une recette qui prend le réglage du propriétaire (Mealie, robot qui le
     * dit) : l'écran « Mon réglage » de cette étape s'ouvre, sans copier la recette.
     */
    fun openOwnStep(id: String, index: Int) {}
}

/**
 * Feuille « Réglage du robot » d'une étape (écrans Étape et Mon réglage) : l'ouvrir, choisir le genre
 * ([program] : clé StepProgram, "" pour aucun), un cran sur [field] (seconds, tempC, speed, pulses), le sens et
 * l'accessoire, reprendre le réglage enregistré, « Utiliser ce réglage », ou la fermer sans rien changer.
 */
interface SettingActions {
    fun openSetting() {}
    fun pickSetting(program: String) {}
    fun stepSetting(field: String, dir: Int) {}
    fun setSettingReverse(reverse: Boolean) {}
    fun setSettingWhisk(whisk: Boolean) {}
    fun restoreSetting() {}
    fun applySetting() {}
    fun closeSetting() {}
}

/**
 * Le réglage du propriétaire d'une étape d'une recette reçue (écran Mon réglage) : la feuille l'envoie au robot,
 * qui le juge et le fera vérifier sur place ; le texte de l'étape ne se change pas.
 */
interface OwnStepActions : SettingActions {
    /** « Revenir au réglage de la recette » : le réglage du propriétaire s'en va. */
    fun resetOwnStep() {}

    /** « Réessayer » quand l'étape n'a pas pu être lue. */
    fun retryOwnStep() {}

    /** Retour à la fiche. */
    fun closeOwnStep() {}
}

/**
 * Éditeur d'une recette perso (écrans Éditeur et Étape) : chaque changement est gardé aussitôt en
 * brouillon sur le téléphone ; le robot juge à l'enregistrement. Aucune méthode ne touche aux réglages machine.
 */
interface EditActions : SettingActions {
    /** « Reprendre le brouillon » / « Recommencer » quand un brouillon est proposé à l'ouverture. */
    fun resumeDraft() {}
    fun discardDraft() {}

    fun setTitle(title: String) {}
    fun setServings(n: Int) {}
    fun setMinutes(m: Int) {}
    fun setDifficulty(d: Int) {}

    /** « Ajouter un ingrédient » : une ligne vide à la fin (ou après la ligne [after], « Suivant » du clavier). */
    fun addIngredient(after: Int = -1) {}
    fun setIngredient(index: Int, text: String) {}
    fun removeIngredient(index: Int) {}

    /** « Ajouter une étape » : une étape vide, ouverte dans l'écran Étape. */
    fun addStep() {}
    fun openStep(index: Int) {}
    fun moveStep(index: Int, delta: Int) {}

    /** Écran Étape : son texte, sa pesée (0 : aucune), la retirer, revenir. */
    fun setStepText(text: String) {}
    fun setStepGrams(grams: Int) {}
    fun removeStep() {}
    fun closeStep() {}

    // Feuille « Réglage du robot » de l'étape ouverte (robot qui prend les réglages) : voir SettingActions.

    /** « Enregistrer sur le robot ». */
    fun save() {}

    /** Version dépassée : reprendre celle du robot, ou garder le brouillon (posé sur la version du robot). */
    fun reloadFromRobot() {}
    fun keepDraft() {}

    /** « Réessayer » quand la recette n'a pas pu être lue. */
    fun retryLoad() {}

    /** Quitter l'éditeur (le brouillon reste sur le téléphone). */
    fun leave() {}
}

/**
 * Photos des recettes, chargées hors du fil principal (cache côté implémentation). Une carte la
 * demande quand elle affiche une recette qui a une photo ([RecipeCard.picture]) ; tant que rien
 * n'arrive, ou si [onLoaded] reçoit null, la vignette de couleur avec les initiales reste.
 */
interface PictureSource {
    /** Demande en cours : [cancel] quand la vue n'en veut plus (recyclée pour une autre recette, détachée). */
    fun interface Request {
        fun cancel()
    }

    /**
     * Demande la photo de la recette [recipeId] pour une largeur d'environ [widthPx] pixels.
     * [onLoaded] est appelé sur le fil principal : pendant cet appel si la photo est en mémoire
     * (peut-être plus petite, puis une seconde fois à la bonne taille), sinon plus tard ; jamais
     * après [Request.cancel].
     */
    fun load(recipeId: String, widthPx: Int, onLoaded: (Bitmap?) -> Unit): Request = DONE

    companion object {
        /** Aucune photo : toujours la vignette de couleur. */
        val NONE = object : PictureSource {}

        /** Rien à annuler. */
        val DONE = Request {}
    }
}

/** Écran Journal. */
interface JournalActions {
    fun filter(filter: JournalFilter) {}

    /** Une recette du journal touchée (elle existe encore au catalogue) : sa fiche, d'où la renvoyer au robot. */
    fun openRecipe(recipeId: String) {}
}

/** Écran Robot (onglet Robot). */
interface RobotActions {
    /** « Oublier » confirmé : ce téléphone se retire du robot, puis `nav.showPairing(true)`. */
    fun forgetPhone() {}

    /** « Renommer » enregistré : le nom de ce téléphone sur le robot ; vide : celui par défaut (« Téléphone 1 »). */
    fun renamePhone(name: String) {}

    /** Interrupteur d'alerte basculé (réglage du téléphone). */
    fun setAlert(kind: AlertKind, on: Boolean) {}

    /** « Autoriser » quand les notifications du téléphone sont refusées. */
    fun allowNotifications() {}

    /** Interrupteur « Suivi en arrière-plan ». */
    fun setBackground(on: Boolean) {}

    /** « Autoriser » quand le téléphone limite l'appli en arrière-plan (batterie). */
    fun allowBackground() {}

    /** Ligne « Notification au repos » : la page d'Android de ce canal (la masquer ou la réafficher). */
    fun idleNoticeSettings() {}

    /** Apparence du téléphone : une des couleurs du robot. */
    fun chooseAccent(color: Int) {}

    /** Apparence du téléphone : thème clair ou sombre. */
    fun chooseTheme(light: Boolean) {}

    /** « Prendre l'apparence du robot ». */
    fun takeRobotLook() {}

    /** « Appliquer au robot » : l'apparence du téléphone sur le robot. */
    fun sendLookToRobot() {}

    /** Langue de l'appli : « auto » (celle du téléphone) ou un code (fr, en…). */
    fun chooseLanguage(code: String) {}

    /** Mises à jour : rechercher sur GitHub, installer la version trouvée, réessayer après une erreur. */
    fun checkUpdate() {}
    fun installUpdate() {}
    fun retryUpdate() {}
    /** « Vérifier au démarrage ». */
    fun setAutoUpdate(on: Boolean) {}
}

/** Écran Appairage. */
interface PairingActions {
    /** Texte lu dans un QR code par l'appareil photo (pas encore vérifié : `PairingPayload.parse`). */
    fun qrRead(text: String) {}

    /** Code à 6 chiffres validé ; [address] : « Adresse du robot » saisie, ou null si le champ n'est pas montré. */
    fun codeSubmitted(code: String, address: String?) {}

    /** « Autoriser l'appareil photo » : demander la permission CAMERA. */
    fun cameraPermissionWanted() {}

    /** L'appareil photo n'a pas pu démarrer (absent, occupé, erreur) ; l'écran propose le code. */
    fun cameraFailed(reason: String) {}
}
