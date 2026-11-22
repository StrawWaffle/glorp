package io.github.strawwaffle.glorp

import androidx.compose.ui.window.ComposeUIViewController
import io.github.strawwaffle.glorp.auth.MemorySessionStore

/**
 * Entry point for an iOS app target, built on a Mac. The login is kept only
 * while the app runs until a Keychain store is written.
 */
fun MainViewController() = ComposeUIViewController {
    GlorpApp(origin = "https://www.destiny.gg", sessions = MemorySessionStore())
}
