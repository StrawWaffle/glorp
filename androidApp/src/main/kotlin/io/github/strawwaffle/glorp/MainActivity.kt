package io.github.strawwaffle.glorp

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
import io.github.strawwaffle.glorp.ui.PictureInPicture

class MainActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    private var videoShowing = false
    private var videoBounds: android.graphics.Rect? = null

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessions = KeystoreSessionStore(applicationContext)
        if (BuildConfig.DEV_IMPORT) {
            importDevSession(sessions)
            // Lets the player's pages be inspected from desktop Chrome.
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        // Dev builds: `am start … --es embed kick/destiny` opens an embed, for
        // trying players without posting links in chat.
        val embed = if (BuildConfig.DEV_IMPORT) intent?.getStringExtra("embed") else null
        addOnPictureInPictureModeChangedListener(pipListener)
        inPip = isInPictureInPictureMode
        setContent {
            GlorpApp(
                origin = BuildConfig.SITE_ORIGIN,
                sessions = sessions,
                initialEmbed = embed,
                pictureInPicture = PictureInPicture(
                    active = inPip,
                    setAvailable = { showing -> videoShowing = showing; updatePipParams() },
                    setVideoBounds = ::onVideoBounds,
                ),
            )
        }
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
    private fun importDevSession(sessions: KeystoreSessionStore) {
        val file = getExternalFilesDir(null)?.resolve("dev-session") ?: return
        if (!file.exists()) return
        val text = file.readText()
        file.delete()
        Session.decode(text.trim().replace("\r", ""))?.let(sessions::save)
    }
}
