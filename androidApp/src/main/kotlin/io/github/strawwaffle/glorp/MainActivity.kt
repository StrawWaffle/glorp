package io.github.strawwaffle.glorp

import io.github.strawwaffle.glorp.diag.Crashes
import io.github.strawwaffle.glorp.diag.AppLog
import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import io.github.strawwaffle.glorp.auth.KeystoreSessionStore
import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.SharedChatPrefs
import io.github.strawwaffle.glorp.ui.PictureInPicture
import io.github.strawwaffle.glorp.update.androidUpdates
import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import io.github.strawwaffle.glorp.notify.Alert
import io.github.strawwaffle.glorp.notify.AlertCheck
import io.github.strawwaffle.glorp.notify.Notifier
import io.github.strawwaffle.glorp.notify.NotifyControls
import io.github.strawwaffle.glorp.notify.NotifyOptions
import io.github.strawwaffle.glorp.notify.OpenRequests

class MainActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    private var videoShowing = false
    private var videoBounds: android.graphics.Rect? = null

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }

    // Android 13+ asks the user before an app may notify.
    private var onPermission: ((Boolean) -> Unit)? = null
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermission?.invoke(granted)
        onPermission = null
    }

    private val notify = object : NotifyControls {
        override val permitted: Boolean get() = Notifier.permitted(applicationContext)
        override fun ask(onResult: (Boolean) -> Unit) {
            if (permitted || Build.VERSION.SDK_INT < 33) return onResult(permitted)
            onPermission = onResult
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        override fun changed() = Notifier.schedule(applicationContext)
        override fun openSystemSettings() = Notifier.openSystemSettings(this@MainActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crashes.install(applicationContext, BuildConfig.VERSION_NAME)
        AppLog.note("app", "started" + if (savedInstanceState != null) " again (restored)" else "")
        enableEdgeToEdge()
        val sessions = KeystoreSessionStore(applicationContext)
        if (BuildConfig.DEV_IMPORT) {
            importDevSession(sessions)
            io.github.strawwaffle.glorp.video.Playback.logLive = true
            // Lets the player's pages be inspected from desktop Chrome.
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        // Dev builds: `am start … --es embed kick/destiny` opens an embed, for
        // trying players without posting links in chat.
        val embed = if (BuildConfig.DEV_IMPORT) intent?.getStringExtra("embed") else null
        val frames = if (BuildConfig.DEV_IMPORT) devFrames() else emptyList()
        // Dev builds: `--es notify-test mention|whisper|live` posts a sample notification, to try where tapping it goes.
        if (BuildConfig.DEV_IMPORT) intent?.getStringExtra("notify-test")?.let(::postTestNotification)
        // Dev builds: `--es crash-test 1` crashes a few seconds in, to try the crash report.
        if (BuildConfig.DEV_IMPORT && intent?.getStringExtra("crash-test") != null) {
            android.os.Handler(mainLooper).postDelayed({ error("Crash test from a dev build") }, 4_000)
        }
        // Releases check GitHub for a newer one; dev builds come over adb.
        val updates = if (!BuildConfig.SELF_UPDATE) null
        else androidUpdates(this, SharedChatPrefs(applicationContext), BuildConfig.VERSION_NAME, lifecycleScope)
        addOnPictureInPictureModeChangedListener(pipListener)
        inPip = isInPictureInPictureMode
        takeOpenRequest(intent)
        // Where the background check asks for mentions (see Notifier).
        SharedChatPrefs(applicationContext).put(NotifyOptions.SERVER, BuildConfig.MENTIONS_URL.ifBlank { null })
        setContent {
            GlorpApp(
                origin = BuildConfig.SITE_ORIGIN,
                sessions = sessions,
                prefs = SharedChatPrefs(applicationContext),
                initialEmbed = embed,
                devFrames = frames,
                updates = updates,
                pictureInPicture = PictureInPicture(
                    active = inPip,
                    setAvailable = { showing -> videoShowing = showing; updatePipParams() },
                    setVideoBounds = ::onVideoBounds,
                ),
                notify = notify,
                diagnostics = Crashes.diagnostics(applicationContext, BuildConfig.VERSION_NAME),
            )
        }
    }

    private fun postTestNotification(kind: String) {
        val alert = when (kind) {
            "live" -> Alert(Alert.Kind.Live, "Destiny is live on Kick", "chillin like a villain", AlertCheck.OPEN_LIVE)
            "whisper" -> Alert(Alert.Kind.Whisper, "Whisper from StrawWaffle", "you there?", AlertCheck.OPEN_WHISPER + "StrawWaffle")
            else -> Alert(
                Alert.Kind.Mention, "mrmouton mentioned you", "YeeLaugh",
                // As the site's mentions give it: to the second.
                "https://rustlesearch.dev/surrounds?channel=Destinygg&date=2026-11-23T02%3A23%3A32Z&username=mrmouton",
            )
        }
        Notifier.post(applicationContext, alert)
    }

    /** A tapped notification says where to go (see Notifier). */
    private fun takeOpenRequest(intent: Intent?) {
        intent?.getStringExtra(Notifier.EXTRA_OPEN)?.let { OpenRequests.pending.value = it }
        intent?.removeExtra(Notifier.EXTRA_OPEN)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenRequest(intent)
    }

    override fun onStart() {
        super.onStart()
        Notifier.onScreen = true
    }

    override fun onStop() {
        Notifier.onScreen = false
        super.onStop()
    }

    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    private fun onVideoBounds(bounds: Rect) {
        // Telling the system is a call into another process: only when the
        // video really moved, and not while the window is changing mode.
        if (isInPictureInPictureMode) return
        val rect = android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
        val old = videoBounds
        if (rect.isEmpty) return
        if (old != null && kotlin.math.abs(old.top - rect.top) < 4 && kotlin.math.abs(old.bottom - rect.bottom) < 4 &&
            kotlin.math.abs(old.left - rect.left) < 4 && kotlin.math.abs(old.right - rect.right) < 4
        ) return
        videoBounds = rect
        updatePipParams()
    }

    /**
     * Android 12 and later shrink the app to the video by themselves when it's
     * left while a video shows; the bounds let the window animate from where
     * the video was.
     */
    private fun pipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply { videoBounds?.let(::setSourceRectHint) }
            .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(videoShowing).setSeamlessResizeEnabled(true) }
            .build()

    private fun updatePipParams() {
        setPictureInPictureParams(pipParams())
    }

    // Before Android 12 the app has to ask when the user leaves it.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && videoShowing && !isInPictureInPictureMode) {
            enterPictureInPictureMode(pipParams())
        }
    }

    /**
     * Dev and debug builds only: a test-site login in the app's external files
     * folder (`Android/data/<package>/files/dev-session`, which adb can write)
     * is moved into the encrypted store and the file deleted. The file holds
     * `k` and a login key, or `c`, `sid` and `rememberme`, one per line.
     * Release builds never look for it.
     */
    /**
     * Dev and debug builds only: chat frames to play as if the server sent
     * them, from `files/dev-frames` (deleted once read), for trying what the
     * test account can't start, such as polls. One frame per line; `sleep N`
     * waits N ms, and `${'$'}NOW` is the time in milliseconds when it's played.
     */
    private fun devFrames(): List<String> {
        val file = getExternalFilesDir(null)?.resolve("dev-frames") ?: return emptyList()
        if (!file.exists()) return emptyList()
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        file.delete()
        return lines
    }

    private fun importDevSession(sessions: KeystoreSessionStore) {
        val file = getExternalFilesDir(null)?.resolve("dev-session") ?: return
        if (!file.exists()) return
        val text = file.readText()
        file.delete()
        Session.decode(text.trim().replace("\r", ""))?.let(sessions::save)
    }
}
