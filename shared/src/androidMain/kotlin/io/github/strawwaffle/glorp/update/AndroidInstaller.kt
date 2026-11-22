package io.github.strawwaffle.glorp.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Downloads a release into the app's cache, checks it against the
 * release's SHA-256, and hands it to Android's package installer, which
 * asks the user to confirm and refuses an APK signed with another key.
 */
class AndroidInstaller(context: Context, private val client: HttpClient) : Installer {
    private val app = context.applicationContext

    override suspend fun install(release: Release, progress: (Int) -> Unit): UpdateState {
        val expected = release.sha256
            ?: return UpdateState.Failed(release, "The release has no checksum, so it can't be checked. Get it from GitHub instead.")
        // Android asks once whether Glorp may install apps.
        if (!app.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(settings) }
            return UpdateState.NeedsPermission(release)
        }
        val file = File(app.cacheDir, "updates/glorp-${release.version}.apk")
        return try {
            val sha = download(release.apkUrl, file, progress)
            if (sha != expected) {
                file.delete()
                return UpdateState.Failed(release, "The download didn't match the release's checksum, so it wasn't installed.")
            }
            withContext(Dispatchers.IO) { commit(file) }
            UpdateState.Installing(release)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            file.delete()
            UpdateState.Failed(release, "The download failed. Check the connection and try again.")
        }
    }

    /** Saves [url] to [file], returning its SHA-256 as hex. */
    private suspend fun download(url: String, file: File, progress: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        file.parentFile?.listFiles()?.forEach { it.delete() }
        file.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        client.prepareGet(url) { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }.execute { response ->
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val total = response.contentLength() ?: -1L
            val body = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            var shown = -1
            file.outputStream().use { out ->
                while (true) {
                    val read = body.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read == 0) { if (body.isClosedForRead) break else continue }
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    done += read
                    if (total > 0) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != shown) { shown = percent; progress(percent) }
                    }
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("glorp.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(app, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
        }
    }
}

/** Hears back from the installer: shows its confirmation screen when it asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
        }
    }
}

/** Glorp's updates on Android, with their own HTTP client. */
fun androidUpdates(context: Context, prefs: io.github.strawwaffle.glorp.chat.ChatPrefs, version: String, scope: kotlinx.coroutines.CoroutineScope): Updates {
    val client = io.github.strawwaffle.glorp.net.createHttpClient()
    return Updates(client, prefs, version, AndroidInstaller(context, client), scope)
}
