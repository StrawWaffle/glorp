package io.github.strawwaffle.glorp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.strawwaffle.glorp.auth.KeystoreSessionStore
import io.github.strawwaffle.glorp.auth.Session

class MainActivity : ComponentActivity() {
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
        setContent { GlorpApp(origin = BuildConfig.SITE_ORIGIN, sessions = sessions, initialEmbed = embed) }
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
