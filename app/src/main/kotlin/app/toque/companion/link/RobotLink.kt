package app.toque.companion.link

import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.CatalogFilter
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CommandReply
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.EditableRecipe
import app.toque.core.companion.EditorWire
import app.toque.core.companion.FavoriteReply
import app.toque.core.companion.RecipeEditRequest
import app.toque.core.companion.JournalPage
import app.toque.core.companion.PairReply
import app.toque.core.companion.PairingPayload
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.StartRequest
import app.toque.core.companion.StateWire
import app.toque.core.companion.WhoAmI
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.companion.client.RobotFinder
import app.toque.core.companion.client.RobotProofException
import app.toque.core.companion.client.StateFollower
import app.toque.core.i18n.tr
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory

/** Où en est la liaison avec le robot. */
enum class LinkPhase {
    /** Aucun robot appairé : écran d'appairage. */
    NOT_PAIRED,
    /** Appairé, en attente du premier état. */
    CONNECTING,
    CONNECTED,
    /** Le robot ne répond plus : le téléphone le cherche (même adresse, puis le réseau). */
    UNREACHABLE,
    /** Le robot a retiré ce téléphone (401) : appairage oublié, retour à l'écran d'appairage. */
    REMOVED,
}

data class LinkStatus(
    val phase: LinkPhase,
    val robotName: String = "",
    /** [LinkPhase.REMOVED] : le message à montrer sur l'écran d'appairage. */
    val message: String? = null,
    /**
     * [LinkPhase.UNREACHABLE] : un appareil répond à l'adresse du robot mais ne prouve pas être lui,
     * plusieurs fois de suite. Le plus probable : le robot a retiré ce téléphone ; il faudra peut-être
     * l'appairer de nouveau (le téléphone ne peut pas en être sûr, voir docs/COMPANION.md, 2.6).
     */
    val maybeRemoved: Boolean = false,
    /** [LinkPhase.UNREACHABLE] : début de la panne (horloge monotone de la liaison). */
    val sinceMono: Long = 0,
    /** Numéro du suivi : change à chaque nouveau suivi (après un arrêt, un nouvel appairage). */
    val follow: Long = 0,
)

/** Résultat d'un appairage. */
sealed class PairResult {
    data class Paired(val robotName: String) : PairResult()
    /** Le robot a refusé : code de l'API (wrong_code, code_expired…) et son message, dans sa langue. */
    data class Refused(val code: String, val message: String) : PairResult()
    /** Le texte scanné n'est pas un QR code d'appairage Toque. */
    object InvalidQr : PairResult()
    /** Le téléphone n'est pas sur un Wi-Fi (pas d'adresse privée). */
    object NotOnWifi : PairResult()
    /** Aucun robot n'a l'appairage ouvert sur ce réseau. */
    object NoRobot : PairResult()
    /** Plusieurs robots ont l'appairage ouvert : demander l'adresse affichée sur le robot (ou le QR code). */
    data class SeveralRobots(val hosts: List<String>) : PairResult()
    /** Réseau, réponse illisible… : [message] affichable. */
    data class Failed(val message: String) : PairResult()
}

/** Réponse du robot à une commande : acceptée (avec sa révision), ou refusée avec son propre message. */
data class CommandOutcome(val ok: Boolean, val revision: Long = 0, val code: String = "", val message: String = "") {
    companion object {
        fun of(r: CommandReply) = CommandOutcome(r.ok, r.revision)

        fun of(t: Throwable): CommandOutcome = when (t) {
            is CompanionException -> CommandOutcome(false, 0, t.error.code, displayable(t))
            is RobotProofException -> CommandOutcome(false, 0, "proof", tr("L'appareil qui répond n'est pas votre robot"))
            is NotPairedException -> CommandOutcome(false, 0, "not_paired", tr("Ce téléphone n'est pas appairé"))
            else -> CommandOutcome(false, 0, "network", tr("Le robot ne répond pas"))
        }

        /** Les refus (règles, appairage) ont un message affichable ; les erreurs de protocole non. */
        fun displayable(e: CompanionException): String {
            val m = e.error.message
            return if (m.isNotBlank() && e.status in DISPLAYABLE) m else tr("Le robot a refusé la demande")
        }

        private val DISPLAYABLE = setOf(403, 409, 410, 504)
    }
}

class NotPairedException : IOException("not paired")

/**
 * La liaison avec le robot, une par appli (créée par l'Application). Garde l'appairage, suit l'état
 * du robot avec [StateFollower] tant que quelqu'un le demande ([follow] : l'activité visible, le
 * service d'alertes), retrouve le robot quand son adresse change, et envoie les commandes.
 *
 * Tout le réseau se fait sur des fils à elle ; les rappels arrivent par [post] (le fil principal
 * sur Android). Kotlin pur : testée sur la JVM contre un vrai hub.
 */
class RobotLink(
    private val store: LinkStore,
    private val post: (Runnable) -> Unit,
    private val ownIpv4: () -> String? = { LocalNetwork.ownIpv4() },
    /** Adresses à sonder, d'après l'adresse du téléphone ; par défaut son /24 ([RobotFinder.subnet]). */
    private val candidates: (String?) -> List<String> = { own -> own?.let { RobotFinder.subnet(it) } ?: emptyList() },
    /** Ports essayés pour l'appairage par code et, après le dernier port connu, pour retrouver le robot. */
    private val ports: List<Int> = listOf(CompanionHub.DEFAULT_PORT),
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    private val waitSeconds: Int = 20,
    private val followerSleep: (Long) -> Unit = { Thread.sleep(it) },
    /** Preuves fausses de suite à l'adresse du robot avant de dire qu'il faudra peut-être appairer de nouveau. */
    private val proofFailuresForRemoved: Int = 2,
    private val locateTimeoutMs: Int = 1_200,
    /** Rythme du suivi permanent (attente, pauses, recherche) ; null : rythme fixe d'avant (tests). */
    private val pacing: FollowPacing? = null,
    /** Horloge qui avance aussi quand le téléphone dort (SystemClock.elapsedRealtime). */
    private val elapsedMs: () -> Long = wallMs,
    /** Réponse du robot reçue, sur le fil du suiveur avant tout le reste : garder le téléphone éveillé un instant. */
    private val onAnswer: () -> Unit = {},
    /** Diagnostic des alertes (jamais de jeton ni d'adresse). */
    private val log: (String) -> Unit = {},
) {
    interface Listener {
        fun onStatus(status: LinkStatus) {}
        /** Nouvel état du robot ; [receivedMono] : son arrivée (base du compte à rebours, horloge [monoMs]). */
        fun onState(state: StateWire, receivedMono: Long) {}
    }

    private val lock = Object()
    /**
     * Un écouteur et ce qu'il a déjà reçu (numéros d'état de la liaison et d'état du robot) : rien
     * de plus ancien ne lui est remis, rien deux fois. Lus et écrits sur le fil de [post] seulement.
     */
    private class Entry(val listener: Listener) {
        var statusSeq = -1L
        var stateSeq = -1L
    }
    private val listeners = CopyOnWriteArrayList<Entry>()
    /** Numéros croissants des changements d'état de la liaison et des états reçus (sous [lock]). */
    private var statusSeq = 0L
    private var stateSeq = 0L
    private val owners = HashSet<String>()
    private val daemon = ThreadFactory { r -> Thread(r, "toque-link").apply { isDaemon = true } }
    private val commands: ExecutorService = Executors.newSingleThreadExecutor(daemon)
    private val reads: ExecutorService = Executors.newFixedThreadPool(3, daemon)

    @Volatile var paired: PairedRobot? = store.load()
        private set
    @Volatile var status: LinkStatus = initialStatus()
        private set
    /** Dernier état reçu pendant le suivi en cours (null hors suivi). Avec [latestMono], son arrivée ([monoMs]). */
    @Volatile var latest: StateWire? = null
        private set
    @Volatile var latestMono: Long = 0
        private set

    private var client: CompanionClient? = paired?.let { clientFor(it) }
    private var follower: StateFollower? = null
    /** Numéro du suiveur actuel : les rappels d'un ancien suiveur sont ignorés. */
    private var generation = 0L
    private var proofFailures = 0
    @Volatile private var closed = false
    /** Les états dans le diagnostic : seulement ceux qui changent quelque chose, plus un compte par minute. */
    private val stateDiag = StateDiag()

    private fun initialStatus() = paired?.let { LinkStatus(LinkPhase.CONNECTING, it.robotName) } ?: LinkStatus(LinkPhase.NOT_PAIRED)

    private fun clientFor(p: PairedRobot) = CompanionClient(p.host, p.port, p.token, p.phoneId, p.robotId)

    /**
     * Ajoute un écouteur ; renvoie de quoi le retirer. Il apprend aussitôt ce que la liaison sait déjà,
     * sans attendre un changement : l'état de la liaison, puis le dernier état du robot s'il y en a un
     * (avec son heure d'arrivée réelle). Remis par [post], jamais pendant cet appel (sur Android), et
     * seulement s'il est encore inscrit ; ce qui arrive ensuite n'est jamais plus ancien.
     */
    fun addListener(l: Listener): () -> Unit {
        val e = Entry(l)
        listeners += e
        post(Runnable { replay(e) })
        return { listeners.remove(e) }
    }

    /** L'heure de [monoMs] maintenant (âge d'un état reçu : maintenant moins son heure d'arrivée). */
    val nowMono: Long get() = monoMs()

    private fun replay(e: Entry) {
        if (e !in listeners) return
        val st: LinkStatus
        val stSeq: Long
        val last: StateWire?
        val lastSeq: Long
        val lastMono: Long
        synchronized(lock) {
            st = status
            stSeq = statusSeq
            last = latest
            lastSeq = stateSeq
            lastMono = latestMono
        }
        deliverStatus(e, st, stSeq)
        if (last != null) deliverState(e, last, lastSeq, lastMono)
    }

    private fun deliverStatus(e: Entry, s: LinkStatus, seq: Long) {
        if (seq <= e.statusSeq || e !in listeners) return
        e.statusSeq = seq
        e.listener.onStatus(s)
    }

    private fun deliverState(e: Entry, state: StateWire, seq: Long, mono: Long) {
        if (seq <= e.stateSeq || e !in listeners) return
        e.stateSeq = seq
        e.listener.onState(state, mono)
    }

    val isFollowing: Boolean get() = synchronized(lock) { follower != null }

    // ------------------------------------------------------------------ suivi

    /** [owner] veut suivre le robot (« ui », « service »…) ; le suivi dure tant qu'il reste un demandeur. */
    fun follow(owner: String) = synchronized(lock) {
        owners += owner
        startFollowerLocked()
    }

    fun unfollow(owner: String) = synchronized(lock) {
        owners -= owner
        if (owners.isEmpty()) stopFollowerLocked()
    }

    private fun startFollowerLocked() {
        val p = paired ?: return
        if (follower != null || owners.isEmpty() || closed) return
        val gen = ++generation
        val f = StateFollower(clientFor(p), FollowerListener(gen), waitSeconds, finder = { relocate(gen) }, sleeper = followerSleep,
            pacing = pacing, elapsedMs = elapsedMs)
        log("suivi démarré (${owners.sorted().joinToString()})")
        stateDiag.reset()
        follower = f
        latest = null
        setStatusLocked(LinkStatus(LinkPhase.CONNECTING, p.robotName))
        f.start()
    }

    private fun stopFollowerLocked() {
        val f = follower ?: return
        log("suivi arrêté")
        follower = null
        generation++
        f.stop()
        latest = null
        paired?.let { setStatusLocked(LinkStatus(LinkPhase.CONNECTING, it.robotName)) }
    }

    private fun setStatusLocked(n: LinkStatus) {
        val s = n.copy(follow = generation)
        if (s == status) return
        status = s
        val seq = ++statusSeq
        post(Runnable { for (e in listeners) deliverStatus(e, s, seq) })
    }

    /**
     * Réessayer tout de suite (alarme de réveil, appli à l'écran). [restart] : couper aussi l'attente en
     * cours pour repartir au rythme du moment. Une attente restée sans réponse bien après son terme
     * (robot éteint pendant que le téléphone dormait) est coupée et comptée comme un échec.
     */
    fun wake(restart: Boolean = false) {
        val f = synchronized(lock) { follower } ?: return
        if (f.cutIfStale()) log("attente sans réponse depuis trop longtemps : coupée")
        else f.nudge(restart)
    }

    private inner class FollowerListener(private val gen: Long) : StateFollower.Listener {
        override fun onAnswer() = this@RobotLink.onAnswer()

        override fun onRetry(failures: Int, delayMs: Long) {
            if (gen == generation) log("nouvel essai dans ${delayMs / 1000} s (échecs de suite : $failures)")
        }

        override fun onState(state: StateWire) {
            val mono = monoMs()
            val seq: Long
            if (gen == generation) for (line in stateDiag.onState(state, mono)) log(line)
            synchronized(lock) {
                if (gen != generation) return
                latest = state
                latestMono = mono
                seq = ++stateSeq
                proofFailures = 0
                val p = paired
                if (p != null) {
                    // Nom, couleur et thème du robot : gardés pour la prochaine ouverture de l'appli.
                    val r = state.robot
                    val q = p.copy(robotName = r.name.ifEmpty { p.robotName }, accent = r.accent, theme = r.theme)
                    if (q != p) {
                        paired = q
                        saveQuietly(q)
                    }
                }
                setStatusLocked(LinkStatus(LinkPhase.CONNECTED, paired?.robotName.orEmpty()))
            }
            post(Runnable { for (e in listeners) deliverState(e, state, seq, mono) })
        }

        override fun onReachable() = synchronized(lock) {
            if (gen != generation) return
            log("robot de nouveau joignable")
            stateDiag.reset()
            proofFailures = 0
            setStatusLocked(LinkStatus(LinkPhase.CONNECTED, paired?.robotName.orEmpty()))
        }

        override fun onUnreachable(error: Exception) = synchronized(lock) {
            if (gen != generation) return
            log("robot injoignable (${error.javaClass.simpleName})")
            stateDiag.reset()
            if (error is RobotProofException) proofFailures++
            setStatusLocked(LinkStatus(LinkPhase.UNREACHABLE, paired?.robotName.orEmpty(),
                maybeRemoved = proofFailures >= proofFailuresForRemoved, sinceMono = monoMs()))
        }

        override fun onUnauthorized() {
            synchronized(lock) {
                if (gen != generation) return
                follower = null
                generation++
            }
            log("jeton refusé : téléphone retiré sur le robot")
            removed()
        }
    }

    /** Le robot a refusé le jeton : ce téléphone a été retiré. L'appairage est oublié. */
    private fun removed() = synchronized(lock) {
        val name = paired?.robotName.orEmpty()
        follower?.stop()
        follower = null
        generation++
        forgetLocally()
        setStatusLocked(LinkStatus(LinkPhase.REMOVED, name,
            message = tr("Ce téléphone a été retiré sur le robot. Appairez-le de nouveau pour suivre le robot.")))
    }

    private fun forgetLocally() {
        try {
            store.clear()
        } catch (_: Exception) {
        }
        paired = null
        client = null
        latest = null
        proofFailures = 0
    }

    private fun saveQuietly(p: PairedRobot) {
        try {
            store.save(p)
        } catch (_: Exception) {
        }
    }

    /**
     * Appelé par le suiveur (sur son fil) après plusieurs échecs : l'adresse connue répond-elle avec
     * une preuve fausse (téléphone retiré ?) ; puis chercher le robot sur le réseau, sans y envoyer
     * le jeton ([RobotFinder.locate]), au dernier port connu puis aux ports par défaut.
     */
    private fun relocate(gen: Long): String? {
        val p = synchronized(lock) { if (gen != generation) return null; paired } ?: return null
        try {
            val probe = CompanionClient(p.host, p.port, p.token, p.phoneId, p.robotId, locateTimeoutMs, locateTimeoutMs)
            if (!probe.verify(locateTimeoutMs)) synchronized(lock) {
                if (gen != generation) return null
                proofFailures++
                if (proofFailures >= proofFailuresForRemoved && status.phase == LinkPhase.UNREACHABLE)
                    setStatusLocked(status.copy(maybeRemoved = true))
            }
        } catch (_: Exception) {
            // Personne à cette adresse : rien à en conclure.
        }
        // Tout le réseau seulement quand le rythme le permet (même /24, rarement en arrière-plan).
        val scan = pacing?.mayScan() ?: true
        log(if (scan) "recherche du robot sur le réseau" else "recherche du robot à sa dernière adresse seulement")
        val list = (listOf(p.host) + if (scan) candidates(ownIpv4()) else emptyList()).distinct()
        for (port in (listOf(p.port) + ports).distinct()) {
            val host = try {
                RobotFinder.locate(list, port, p.token, p.phoneId, p.robotId, timeoutMs = locateTimeoutMs)
            } catch (_: Exception) {
                null
            } ?: continue
            synchronized(lock) {
                if (gen != generation || closed) return null
                val q = (paired ?: return null).copy(host = host, port = port)
                paired = q
                saveQuietly(q)
                client?.let { it.host = host; it.port = port }
                follower?.client?.port = port
                proofFailures = 0
            }
            log("robot retrouvé")
            return host
        }
        return null
    }

    // ------------------------------------------------------------------ appairage (écran à venir)

    /** Appairage par le texte du QR code scanné sur le robot. */
    fun pairWithQr(text: String, done: (PairResult) -> Unit) {
        val payload = PairingPayload.parse(text)
        if (payload == null) {
            post(Runnable { done(PairResult.InvalidQr) })
            return
        }
        background(reads, done) {
            val c = CompanionClient(payload.host, payload.port)
            pairResult(c, "qr") { c.pairWithPayload(payload) }
        }
    }

    /**
     * Appairage par le code à six chiffres : cherche les robots qui ont l'appairage ouvert sur le
     * réseau du téléphone ([RobotFinder.scan]) et n'essaie le code que si un seul répond.
     */
    fun pairWithCode(code: String, done: (PairResult) -> Unit) = background(reads, done) {
        val list = candidates(ownIpv4())
        if (list.isEmpty()) return@background PairResult.NotOnWifi
        val found = ports.distinct().flatMap { port -> RobotFinder.scan(list, port).filter { it.hello.pairing }.map { it.host to port } }
        when (found.size) {
            0 -> PairResult.NoRobot
            1 -> {
                val (host, port) = found[0]
                val c = CompanionClient(host, port)
                pairResult(c, "code") { c.pairWithCode(code.trim()) }
            }
            else -> PairResult.SeveralRobots(found.map { it.first }.distinct())
        }
    }

    /** Appairage par code avec l'adresse affichée sur le robot (quand plusieurs robots répondent). */
    fun pairWithCodeAt(host: String, code: String, done: (PairResult) -> Unit, port: Int = ports.first()) = background(reads, done) {
        val h = host.trim()
        if (!IPV4.matches(h)) return@background PairResult.Failed(tr("Adresse invalide"))
        val c = CompanionClient(h, port)
        pairResult(c, "code") { c.pairWithCode(code.trim()) }
    }

    private fun pairResult(c: CompanionClient, method: String, pair: () -> PairReply): PairResult = try {
        val r = pair()
        val p = PairedRobot(c.host, c.port, r.token, r.phone.id, r.robot.id, r.robot.name, wallMs(), method, r.robot.accent, r.robot.theme)
        adopt(p)
        PairResult.Paired(p.robotName)
    } catch (e: CompanionException) {
        PairResult.Refused(e.error.code, CommandOutcome.displayable(e))
    } catch (e: IOException) {
        PairResult.Failed(tr("Le robot ne répond pas"))
    }

    private fun adopt(p: PairedRobot) {
        store.save(p)
        synchronized(lock) {
            follower?.stop()
            follower = null
            generation++
            paired = p
            client = clientFor(p)
            proofFailures = 0
            latest = null
            setStatusLocked(LinkStatus(LinkPhase.CONNECTING, p.robotName))
            startFollowerLocked()
        }
    }

    /**
     * « Oublier » : retire ce téléphone de la liste du robot, puis oublie l'appairage ici, même si le
     * robot ne répond pas ([CommandOutcome.ok] dit s'il l'a confirmé).
     */
    fun forget(done: (CommandOutcome) -> Unit) {
        val c = synchronized(lock) { client }
        if (c == null) {
            post(Runnable { done(CommandOutcome.of(NotPairedException())) })
            return
        }
        background(commands, done) {
            val out = try {
                CommandOutcome.of(c.forget())
            } catch (e: Exception) {
                CommandOutcome.of(e)
            }
            synchronized(lock) {
                follower?.stop()
                follower = null
                generation++
                forgetLocally()
                setStatusLocked(LinkStatus(LinkPhase.NOT_PAIRED))
            }
            out
        }
    }

    // ------------------------------------------------------------------ commandes (le robot décide)

    fun pause(done: (CommandOutcome) -> Unit) = command(done) { it.pause() }
    fun resume(done: (CommandOutcome) -> Unit) = command(done) { it.resume() }
    fun stop(done: (CommandOutcome) -> Unit) = command(done) { it.stop() }
    fun tare(done: (CommandOutcome) -> Unit) = command(done) { it.tare() }
    /** « OK » : ferme le programme terminé ou arrêté affiché sur le robot. */
    fun clear(done: (CommandOutcome) -> Unit) = command(done) { it.clear() }
    fun adjust(req: AdjustRequest, done: (CommandOutcome) -> Unit) = command(done) { it.adjust(req) }
    fun start(req: StartRequest, done: (CommandOutcome) -> Unit) = command(done) { it.start(req) }
    fun sendRecipe(id: String, done: (CommandOutcome) -> Unit) = command(done) { it.sendRecipe(id) }
    fun removeWaiting(done: (CommandOutcome) -> Unit) = command(done) { it.removeWaiting() }
    /** « Arrêter la recette » guidée ouverte sur le robot (state.can.stopRecipe). */
    fun stopRecipe(done: (CommandOutcome) -> Unit) = command(done) { it.stopRecipe() }

    /** Couleur et/ou thème du robot (state.can.setAppearance) ; null : inchangé. */
    fun setAppearance(accent: String?, theme: String?, done: (CommandOutcome) -> Unit) = command(done) { it.setAppearance(accent, theme) }
    /** Favori du robot pour la recette [id] (state.can.favorite) ; rend l'état enregistré. */
    fun setFavorite(id: String, favorite: Boolean, done: (Result<FavoriteReply>) -> Unit) =
        request(commands, { it.setFavorite(id, favorite) }, done)

    /** Une recette perso telle qu'enregistrée, avec sa version (state.can.editRecipes). */
    fun editable(id: String, done: (Result<EditableRecipe>) -> Unit) = request(reads, { it.editable(id) }, done)
    /** Enregistre une recette écrite ici : [id] null pour une nouvelle (le robot choisit son identifiant). */
    fun writeRecipe(id: String?, req: RecipeEditRequest, done: (Result<EditableRecipe>) -> Unit) =
        request(commands, { if (id == null) it.createRecipe(req) else it.saveRecipe(id, req) }, done)
    /** Les bornes de l'éditeur et ce que son éditeur d'étape propose (GET /api/editor), pour une recette nouvelle. */
    fun editor(done: (Result<EditorWire>) -> Unit) = request(reads, { it.editor() }, done)
    /** « Ma version » d'une recette Lidl : la copie enregistrée sur le robot. */
    fun copyRecipe(id: String, done: (Result<EditableRecipe>) -> Unit) = request(commands, { it.copyRecipe(id) }, done)

    private fun command(done: (CommandOutcome) -> Unit, call: (CompanionClient) -> CommandReply) =
        request(commands, call) { r -> done(r.fold({ CommandOutcome.of(it) }, { CommandOutcome.of(it) })) }

    // ------------------------------------------------------------------ lectures (écrans à venir)

    fun journal(after: Long, limit: Int = 100, done: (Result<JournalPage>) -> Unit) = request(reads, { it.journal(after, limit) }, done)
    fun recipes(text: String, filter: CatalogFilter, offset: Int, limit: Int, category: String = "", done: (Result<CatalogPageWire>) -> Unit) =
        request(reads, { it.recipes(text, filter, offset, limit, category) }, done)
    /** Fiche d'une recette ; [base] : à son propre nombre de personnes (un robot plus ancien l'ignore). */
    fun recipe(id: String, base: Boolean = false, done: (Result<RecipeDetail>) -> Unit) = request(reads, { it.recipe(id, base) }, done)
    /** Nombre de personnes enregistré sur le robot ; rend la fiche à ce nombre. */
    fun setServings(id: String, people: Int, done: (Result<RecipeDetail>) -> Unit) = request(commands, { it.setServings(id, people) }, done)
    /** Image d'une recette (null si elle n'en a pas). */
    fun image(id: String, done: (Result<ByteArray?>) -> Unit) = request(reads, { it.image(id) }, done)
    fun robot(done: (Result<WhoAmI>) -> Unit) = request(reads, { it.robot() }, done)

    /** Appel quelconque du client, hors du fil principal ; un 401 oublie l'appairage (téléphone retiré). */
    fun <T> request(work: (CompanionClient) -> T, done: (Result<T>) -> Unit) = request(reads, work, done)

    private fun <T> request(exec: ExecutorService, work: (CompanionClient) -> T, done: (Result<T>) -> Unit) {
        val c = synchronized(lock) { client }
        if (c == null) {
            post(Runnable { done(Result.failure(NotPairedException())) })
            return
        }
        background(exec, done) {
            try {
                Result.success(work(c))
            } catch (e: Exception) {
                if (e is CompanionException && e.unauthorized && synchronized(lock) { client === c }) removed()
                Result.failure(e)
            }
        }
    }

    private fun <T> background(exec: ExecutorService, done: (T) -> Unit, work: () -> T) {
        if (closed) return
        try {
            exec.execute {
                val r = work()
                if (!closed) post(Runnable { done(r) })
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Fin de l'appli : arrête le suivi et les fils (rien ne reste derrière). */
    fun close() {
        synchronized(lock) {
            closed = true
            owners.clear()
            follower?.stop()
            follower = null
            generation++
        }
        commands.shutdownNow()
        reads.shutdownNow()
        listeners.clear()
    }

    companion object {
        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    }
}
