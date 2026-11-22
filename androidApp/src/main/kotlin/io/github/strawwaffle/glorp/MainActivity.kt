package io.github.strawwaffle.glorp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.strawwaffle.glorp.auth.KeystoreSessionStore
import io.github.strawwaffle.glorp.auth.Session
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessions = KeystoreSessionStore(applicationContext)
        if (BuildConfig.DEBUG) importDevSession(sessions)
        setContent { GlorpApp(origin = BuildConfig.SITE_ORIGIN, sessions = sessions) }
    }

    /**
     * Dev builds only: a test-site login placed in the app's private files
     * (`sid` on the first line, `rememberme` on the second, written there with
     * `adb shell run-as`) is moved into the encrypted store and the file is
     * deleted. Release builds never look for it.
     */
    private fun importDevSession(sessions: KeystoreSessionStore) {
        val file = File(filesDir, "dev-session")
        if (!file.exists()) return
        val lines = file.readLines().map { it.trim() }
        file.delete()
        Session.fromPasted(lines.getOrElse(0) { "" }, lines.getOrElse(1) { "" })?.let(sessions::save)
    }
}
