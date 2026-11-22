package io.github.strawwaffle.glorp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.setSingletonImageLoaderFactory
import io.github.strawwaffle.glorp.chat.ChatViewModel
import io.github.strawwaffle.glorp.ui.ChatScreen
import io.github.strawwaffle.glorp.ui.GlorpTheme
import io.github.strawwaffle.glorp.ui.glorpImageLoader

/** The app's root. [origin] is the site to talk to: destiny.gg, or its test site in dev builds. */
@Composable
fun GlorpApp(origin: String) {
    setSingletonImageLoaderFactory { context -> glorpImageLoader(context) }
    GlorpTheme {
        val chat = viewModel { ChatViewModel(origin) }
        // The socket is held only while the app is on screen. Leaving the app
        // closes it; coming back reconnects, and the backlog chat sends on
        // connect fills the gap.
        LifecycleStartEffect(chat) {
            chat.start()
            onStopOrDispose { chat.stop() }
        }
        val lines by chat.lines.collectAsStateWithLifecycle()
        val flairs by chat.flairs.collectAsStateWithLifecycle()
        val emotes by chat.emotes.collectAsStateWithLifecycle()
        val state by chat.socketState.collectAsStateWithLifecycle()
        ChatScreen(lines, flairs, emotes, state)
    }
}
