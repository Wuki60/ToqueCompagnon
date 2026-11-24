package app.toque.companion.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.toque.core.i18n.tr
import app.toque.core.update.Release
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException

/**
 * Mises à jour du compagnon depuis les versions publiées sur GitHub (comme app/update/Updater.kt de
 * Toque) : seule partie de l'appli qui passe par Internet, et seulement vers GitHub. Méthodes
 * bloquantes, hors du fil principal.
 */
class Updater(private val context: Context) : UpdateSource {
    @Suppress("DEPRECATION")
    private val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val installedVersion: String = info.versionName ?: "0"
    @Suppress("DEPRECATION")
    private val installedCode: Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    override fun latest(): Release {
        val c = open(CompanionReleases.LATEST_URL, "application/vnd.github+json")
        try {
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw UpdateFailure(tr("Aucune version publiée sur GitHub pour l'instant."))
                403, 429 -> throw UpdateFailure(tr("GitHub limite les vérifications : réessayez dans une heure."))
                else -> throw UpdateFailure(tr("GitHub a répondu %d.", code))
            }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return try {
                CompanionReleases.parse(text)
            } catch (_: IllegalArgumentException) {
                throw UpdateFailure(tr("Réponse de GitHub illisible."))
            }
        } catch (e: IOException) {
            throw network(e)
        } finally {
            c.disconnect()
        }
    }

    override fun download(r: Release, progress: (Int) -> Unit): Any {
        val url = r.apkUrl ?: throw UpdateFailure(tr("La version %s ne contient pas d'appli à installer.", r.version))
        val dir = File(context.cacheDir, "maj")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "ToqueCompagnon-${r.version}.apk")
        val c = open(url, "application/octet-stream")
        try {
            if (c.responseCode != 200) throw UpdateFailure(tr("Téléchargement refusé (code %d).", c.responseCode))
            val total = if (r.apkSize > 0) r.apkSize else c.contentLengthLong
            if (total > MAX_APK) throw UpdateFailure(tr("Le fichier téléchargé n'est pas une appli valide."))
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_APK) throw UpdateFailure(tr("Le fichier téléchargé n'est pas une appli valide."))
                        out.write(buf, 0, n)
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != shown) { shown = pct; progress(pct) }
                        }
                    }
                }
            }
            if (r.apkSize > 0 && done != r.apkSize) throw UpdateFailure(tr("Téléchargement incomplet : réessayez."))
        } catch (e: IOException) {
            file.delete()
            throw network(e)
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            c.disconnect()
        }
        verify(file)
        return file
    }

    /** Le fichier est bien une version plus récente du compagnon (Android vérifie ensuite la signature). */
    @Suppress("DEPRECATION")
    private fun verify(file: File) {
        val pi = context.packageManager.getPackageArchiveInfo(file.path, 0)
        val code = pi?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        val problem = when {
            pi == null -> tr("Le fichier téléchargé n'est pas une appli valide.")
            pi.packageName != context.packageName -> tr("Le fichier téléchargé n'est pas Toque Compagnon.")
            code == null || code <= installedCode -> tr("Cette version n'est pas plus récente que celle installée.")
            else -> null
        }
        if (problem != null) {
            file.delete()
            throw UpdateFailure(problem)
        }
    }

    /** Confie l'APK à l'installateur d'Android, qui demande confirmation (et, la première fois, l'autorisation). */
    override fun install(apk: Any) {
        val file = apk as File
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            session.openWrite("compagnon.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw UpdateFailure(tr("Installation impossible : %s", e.message ?: e.javaClass.simpleName))
        } finally {
            session.close()
        }
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        if (!CompanionReleases.trusted(url)) throw UpdateFailure(tr("Le fichier téléchargé n'est pas une appli valide."))
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.useCaches = false
        c.setRequestProperty("User-Agent", "ToqueCompagnon/$installedVersion (Android)")
        c.setRequestProperty("Accept", accept)
        return c
    }

    private fun network(e: IOException): UpdateFailure {
        Log.w("Toque", "mise à jour : $e")
        return if (e is SSLException) UpdateFailure(tr("Connexion sécurisée à GitHub impossible. Vérifiez la date et l'heure du téléphone."))
        else UpdateFailure(tr("Pas de connexion à GitHub. Vérifiez la connexion à Internet."))
    }

    companion object {
        /** Un APK du compagnon pèse quelques Mo : au-delà, ce n'est pas lui. */
        const val MAX_APK = 64L * 1024 * 1024

        /** Échec signalé par l'installateur d'Android (fil principal). */
        @Volatile var onInstallError: ((String) -> Unit)? = null

        fun explain(result: String): String = when {
            result.contains("UPDATE_INCOMPATIBLE") || result.contains("SIGNATURE") ->
                tr("La version installée n'a pas la même signature. Désinstallez Toque Compagnon une fois, puis installez la version de GitHub.")
            result.contains("INSUFFICIENT_STORAGE") -> tr("Espace de stockage insuffisant sur le téléphone.")
            result.contains("VERSION_DOWNGRADE") -> tr("Cette version n'est pas plus récente que celle installée.")
            else -> tr("Installation refusée : %s", result.lines().lastOrNull { it.isNotBlank() } ?: result)
        }
    }
}

/** Résultat de l'installateur d'Android : confirmation à afficher, ou refus à dire. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    c.startActivity(confirm)
                } catch (e: Exception) {
                    Log.w("Toque", "confirmation de la mise à jour impossible : $e")
                    post(tr("Installation impossible : %s", e.message ?: e.javaClass.simpleName))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $status"
                Log.w("Toque", "mise à jour refusée : $message")
                post(if (status == PackageInstaller.STATUS_FAILURE_ABORTED) tr("Installation annulée.") else Updater.explain(message))
            }
        }
    }

    private fun post(text: String) {
        Handler(Looper.getMainLooper()).post { Updater.onInstallError?.invoke(text) }
    }
}
