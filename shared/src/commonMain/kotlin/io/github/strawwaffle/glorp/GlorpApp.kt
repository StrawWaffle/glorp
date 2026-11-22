package io.github.strawwaffle.glorp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.setSingletonImageLoaderFactory
import io.github.strawwaffle.glorp.auth.SessionStore
import io.github.strawwaffle.glorp.chat.ChatViewModel
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.ui.Bigscreen
import io.github.strawwaffle.glorp.ui.ChatScreen
import io.github.strawwaffle.glorp.ui.GlorpTheme
import io.github.strawwaffle.glorp.ui.SignInScreen
import io.github.strawwaffle.glorp.ui.glorpImageLoader

/**
 * The app's root. [origin] is the site to talk to: destiny.gg, or its test
 * site in dev builds. [sessions] keeps the login between launches.
 */
@Composable
fun GlorpApp(origin: String, sessions: SessionStore, initialEmbed: String? = null) {
    setSingletonImageLoaderFactory { context -> glorpImageLoader(context) }
    GlorpTheme {
        val chat = viewModel { ChatViewModel(origin, sessions).also { vm -> initialEmbed?.let(Embed::parse)?.let(vm::watch) } }
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
        val account by chat.account.collectAsStateWithLifecycle()
        val signingIn by chat.signingIn.collectAsStateWithLifecycle()
        var signInOpen by rememberSaveable { mutableStateOf(false) }
        val live by chat.live.collectAsStateWithLifecycle()
        val picked by chat.picked.collectAsStateWithLifecycle()
        var playerShown by rememberSaveable { mutableStateOf(true) }
        val embed = picked ?: live.defaultEmbed()

        if (signInOpen && account == null) {
            SignInScreen(
                origin = origin,
                host = chat.host,
                busy = signingIn,
                onSession = { session, result ->
                    chat.signIn(session) { error ->
                        result(error)
                        if (error == null) signInOpen = false
                    }
                },
                onClose = { signInOpen = false },
            )
        } else {
            ChatScreen(
                lines, flairs, emotes, state, account,
                onSignIn = { signInOpen = true },
                onSignOut = chat::signOut,
                onSend = chat::send,
                onEmbed = { chat.watch(it); playerShown = true },
                player = if (!playerShown) null else {
                    {
                        Bigscreen(
                            embed = embed,
                            playerUrl = embed?.let(chat::playerUrl),
                            origin = chat.playerOrigin,
                            live = live,
                            picked = picked != null,
                            onPick = chat::watch,
                            onHide = { playerShown = false },
                        )
                    }
                },
                onShowPlayer = if (playerShown) null else { { playerShown = true } },
            )
        }
    }
}
