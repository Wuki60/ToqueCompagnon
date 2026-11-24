package app.toque.companion

import app.toque.companion.WireFixtures.T
import app.toque.companion.WireFixtures.UTC
import app.toque.companion.flows.AlertSwitches
import app.toque.companion.flows.CatalogFlow
import app.toque.companion.flows.JournalFlow
import app.toque.companion.flows.PhoneClock
import app.toque.companion.flows.RobotFlow
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairedRobot
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.Route
import app.toque.companion.ui.Screens
import app.toque.companion.ui.screens.AppairageState
import app.toque.companion.ui.screens.EnvoyeeState
import app.toque.companion.ui.screens.JournalFilter
import app.toque.companion.ui.screens.JournalState
import app.toque.companion.ui.screens.PairingError
import app.toque.companion.ui.screens.RecettesState
import app.toque.companion.ui.screens.SendStatus
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.JournalPage
import app.toque.core.companion.RecipeDetail
import app.toque.core.i18n.tr
import app.toque.core.json.Json

/**
 * Les écrans des onglets Recettes, Journal et Robot, et l'appairage, rendus depuis des données du
 * fil (JSON tel que le robot l'écrit) par les flux de l'appli (préfixe « Fil- »).
 */
object TabShots {
    @Suppress("UNCHECKED_CAST")
    private fun obj(json: String) = Json.parse(json) as Map<String, Any?>

    private val catalog = CatalogPageWire.fromMap(obj("""
        {"total":61,"offset":0,"items":[
         {"id":"perso-veloute-de-potimarron","title":"Velouté de potimarron","category":"Soupes","totalMinutes":35,"favorite":true,"user":true,"hasImage":false,"machineSteps":3,"rating":0},
         {"id":"essai-1","title":"Pâte à pizza","category":"Pâtes","totalMinutes":7,"favorite":false,"user":false,"hasImage":false,"machineSteps":2,"rating":4.5},
         {"id":"essai-2","title":"Risotto aux champignons et au parmesan affiné","category":"Plats","totalMinutes":40,"favorite":true,"user":false,"hasImage":false,"machineSteps":4,"rating":0},
         {"id":"essai-3","title":"Compote de pommes","category":"Desserts","totalMinutes":20,"favorite":false,"user":false,"hasImage":false,"machineSteps":1,"rating":0},
         {"id":"essai-4","title":"Soupe de légumes","category":"Soupes","totalMinutes":30,"favorite":false,"user":false,"hasImage":false,"machineSteps":2,"rating":0},
         {"id":"essai-5","title":"Crème anglaise","category":"","totalMinutes":0,"favorite":false,"user":true,"hasImage":false,"machineSteps":1,"rating":0}]}"""))

    private val detail = RecipeDetail.fromMap(obj("""
        {"id":"perso-veloute-de-potimarron","title":"Velouté de potimarron","category":"Soupes","servings":4,"servingUnit":"",
         "prepMinutes":10,"totalMinutes":35,"difficulty":1,"favorite":true,"user":true,"author":"","rating":0,"ratingCount":0,"hasImage":false,
         "ingredients":[{"title":"","items":[
           {"qty":"20","unit":"g","name":"Beurre","note":"","display":"20 g Beurre"},
           {"qty":"1","unit":"","name":"Oignon","note":"émincé","display":"1 Oignon, émincé"},
           {"qty":"600","unit":"g","name":"Potimarron","note":"en dés","display":"600 g Potimarron, en dés"},
           {"qty":"500","unit":"g","name":"Eau","note":"","display":"500 g Eau"}]}],
         "steps":[
           {"index":0,"text":"Couper le potimarron et l'oignon en dés.","machine":null,"weigh":null,"needsReview":false,"hotBlendOnRobot":false},
           {"index":1,"text":"Mettre le beurre dans le bol.","machine":null,"weigh":{"label":"Beurre","grams":20,"weighedG":null},"needsReview":false,"hotBlendOnRobot":false},
           {"index":2,"text":"Faire revenir l'oignon.","machine":{"program":"manuel","seconds":180,"speed":1,"tempC":100,"reverse":false,"accessory":"BLADE","pulses":0,"summary":"3 min · 100 °C · vitesse 1"},"weigh":null,"needsReview":false,"hotBlendOnRobot":false},
           {"index":3,"text":"Laisser cuire jusqu'à ce que le potimarron soit tendre.","machine":{"program":"manuel","seconds":1200,"speed":1,"tempC":100,"reverse":false,"accessory":"BLADE","pulses":0,"summary":"20 min · 100 °C · vitesse 1"},"weigh":null,"needsReview":false,"hotBlendOnRobot":false}]}"""))

    private val minute = 60_000L

    /** Aujourd'hui (T = 12:00 UTC) : une recette terminée avec ses étapes, une cuisson arrêtée ; hier : un pétrissage. */
    private val journal = JournalPage.fromMap(obj("""
        {"lastSeq":12,"entries":[
         {"seq":12,"at":${T - 10 * minute},"kind":"job_stopped","text":"Arrêté depuis Téléphone 1 après 2 min 10 s","alert":"job_end","origin":{"kind":"phone","phoneId":"1eeddd36","name":"Téléphone 1","self":true},"activityId":3,"stepIndex":-1,"elapsedMs":130000,"detail":""},
         {"seq":8,"at":${T - 70 * minute},"kind":"step_started","text":"Étape 4 lancée : 20 min · 100 °C · vitesse 1","alert":"","origin":{"kind":"robot"},"activityId":2,"stepIndex":3,"elapsedMs":0,"detail":""},
         {"seq":6,"at":${T - 80 * minute},"kind":"step_started","text":"Étape 3 lancée : 3 min · 100 °C · vitesse 1","alert":"","origin":{"kind":"robot"},"activityId":2,"stepIndex":2,"elapsedMs":0,"detail":""},
         {"seq":2,"at":${T - 26 * 60 * minute},"kind":"job_finished","text":"Terminé : Pétrir","alert":"job_end","origin":{"kind":"robot"},"activityId":1,"stepIndex":-1,"elapsedMs":45000,"detail":""}],
         "activities":[
         {"id":3,"kind":"manual","title":"Cuisson manuelle","recipeId":"","stepCount":0,"handSteps":0,"startedAt":${T - 13 * minute},"endedAt":${T - 10 * minute},"outcome":"stopped","summary":"3 min · 60 °C · sans mélange","totalMs":180000,"endOrigin":{"kind":"phone","phoneId":"1eeddd36","name":"Téléphone 1","self":true}},
         {"id":2,"kind":"recipe","title":"Velouté de potimarron","recipeId":"perso-veloute-de-potimarron","stepCount":4,"handSteps":2,"startedAt":${T - 95 * minute},"endedAt":${T - 48 * minute},"outcome":"finished","summary":"","totalMs":0,"endOrigin":null},
         {"id":1,"kind":"program","title":"Pétrir","recipeId":"","stepCount":0,"handSteps":0,"startedAt":${T - 26 * 60 * minute - 45000},"endedAt":${T - 26 * 60 * minute},"outcome":"finished","summary":"45 s","totalMs":45000,"endOrigin":null}]}"""))

    private val clock = PhoneClock(wallMs = { T }, zone = { UTC })

    private fun link(): RobotLink = RobotLink(MemoryLinkStore(PairedRobot("192.168.0.12", 8088, "0".repeat(64), "1eeddd36", "a1b2c3d4e5f6",
        "Monsieur Cuisine Connect", T - 24 * 60 * minute, "qr")), post = { it.run() })

    private val switches = object : AlertSwitches {
        override fun enabled() = app.toque.companion.alerts.AlertKind.values().filter { it.defaultOn }.toSet()
        override fun set(kind: app.toque.companion.alerts.AlertKind, on: Boolean) {}
    }

    val all: List<Triple<String, Route, (Screens) -> Unit>> = listOf(
        Triple("Fil-Recettes", Route.RECETTES) { s -> s.recettes.render(RecettesState(items = catalog.items.map { CatalogFlow.card(it) }, hasMore = true)) },
        Triple("Fil-Fiche", Route.FICHE) { s -> s.fiche.render(CatalogFlow.fiche(detail)) },
        Triple("Fil-FicheHorsLigne", Route.FICHE) { s ->
            s.fiche.render(CatalogFlow.fiche(detail).copy(send = SendStatus.BLOCKED, sendNote = tr("Le robot ne répond pas pour l'instant.")))
        },
        Triple("Fil-Envoyee", Route.ENVOYEE) { s ->
            val f = CatalogFlow.fiche(detail)
            s.envoyee.render(EnvoyeeState(f.id, f.title, f.personal, f.category, f.stepCount, f.picture, f.durationMin, f.servings,
                sentAt = clock.hhmm(T - 2 * minute), receivedAt = clock.hhmm(T - 2 * minute)))
        },
        Triple("Fil-EnvoyeeRetiree", Route.ENVOYEE) { s ->
            val f = CatalogFlow.fiche(detail)
            s.envoyee.render(EnvoyeeState(f.id, f.title, f.personal, f.category, f.stepCount, f.picture, f.durationMin, f.servings,
                sentAt = clock.hhmm(T - 9 * minute), receivedAt = clock.hhmm(T - 9 * minute), sentBy = "Téléphone 2", removed = true))
        },
        Triple("Fil-Journal", Route.JOURNAL) { s ->
            val flow = JournalFlow(link(), {}, clock)
            s.journal.render(JournalState(days = flow.present(journal, JournalFilter.ALL)))
            flow.close()
        },
        Triple("Fil-Robot", Route.ROBOT) { s ->
            val flow = RobotFlow(link(), switches, { s.robot.render(it) }, notificationsAllowed = { true }, clock = clock)
            flow.onState(WireFixtures.state(), 0)
            flow.close()
        },
        Triple("Fil-RobotNotificationsRefusees", Route.ROBOT) { s ->
            val flow = RobotFlow(link(), switches, { s.robot.render(it) }, notificationsAllowed = { false }, clock = clock)
            flow.onState(WireFixtures.state(), 0)
            flow.close()
        },
        Triple("Fil-AppairageRetire", Route.APPAIRAGE) { s ->
            s.appairage.render(AppairageState(message = tr("Ce téléphone a été retiré sur le robot. Appairez-le de nouveau pour suivre le robot.")))
        },
        Triple("Fil-AppairagePlusieurs", Route.APPAIRAGE) { s ->
            s.appairage.render(AppairageState(error = PairingError.SEVERAL_ROBOTS, askAddress = true))
            s.appairage.showCodeEntry(true)
        },
        Triple("Fil-AppairageComplet", Route.APPAIRAGE) { s -> s.appairage.render(AppairageState(error = PairingError.TOO_MANY_PHONES)) },
    )
}
