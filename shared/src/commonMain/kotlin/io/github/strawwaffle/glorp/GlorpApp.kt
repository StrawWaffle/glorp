package io.github.strawwaffle.glorp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.setSingletonImageLoaderFactory
import io.github.strawwaffle.glorp.auth.SessionStore
import io.github.strawwaffle.glorp.chat.ChatPrefs
import io.github.strawwaffle.glorp.chat.ChatViewModel
import io.github.strawwaffle.glorp.chat.MemoryChatPrefs
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.ui.Bigscreen
import io.github.strawwaffle.glorp.ui.ChatScreen
import io.github.strawwaffle.glorp.ui.GlorpTheme
import io.github.strawwaffle.glorp.ui.LocalBackgroundAudio
import androidx.compose.runtime.remember
import io.github.strawwaffle.glorp.community.CommunityEnv
import io.github.strawwaffle.glorp.community.DinkDonkScreen
import io.github.strawwaffle.glorp.community.PollArchiveScreen
import io.github.strawwaffle.glorp.community.RustleSearchScreen
import io.github.strawwaffle.glorp.ui.MainMenu
import io.github.strawwaffle.glorp.ui.OnBack
import io.github.strawwaffle.glorp.ui.LocalPictureInPicture
import io.github.strawwaffle.glorp.ui.PictureInPicture
import io.github.strawwaffle.glorp.ui.SignInScreen
import io.github.strawwaffle.glorp.ui.glorpImageLoader

/**
 * The app's root. [origin] is the site to talk to: destiny.gg, or its test
 * site in dev builds. [sessions] keeps the login between launches.
 */
@Composable
fun GlorpApp(
    origin: String,
    sessions: SessionStore,
    prefs: ChatPrefs = MemoryChatPrefs(),
    initialEmbed: String? = null,
    pictureInPicture: PictureInPicture = PictureInPicture(),
) {
    setSingletonImageLoaderFactory { context -> glorpImageLoader(context) }
    // Keep a stream's sound going with the screen off: on unless turned off in the menu.
    var backgroundAudio by remember { mutableStateOf(prefs.get(BACKGROUND_AUDIO) != "false") }
    CompositionLocalProvider(
        LocalPictureInPicture provides pictureInPicture,
        LocalBackgroundAudio provides backgroundAudio,
    ) { GlorpTheme {
        val chat = viewModel { ChatViewModel(origin, sessions, prefs).also { vm -> initialEmbed?.let(Embed::parse)?.let(vm::watch) } }
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
        // Chat, the menu, or signing in. Back goes chat -> menu -> out of the
        // app; signing in goes back to the menu.
        var screen by rememberSaveable { mutableStateOf("chat") }
        var communityArg by rememberSaveable { mutableStateOf<String?>(null) }
        val live by chat.live.collectAsStateWithLifecycle()
        val picked by chat.picked.collectAsStateWithLifecycle()
        var playerShown by rememberSaveable { mutableStateOf(true) }
        val embed = picked ?: live.defaultEmbed()
        // Leaving the app shrinks it to the video only while there is one.
        val canShrink = playerShown && embed != null
        LaunchedEffect(canShrink) { pictureInPicture.setAvailable(canShrink) }
        if (screen == "signin" && account != null) screen = "menu"
        OnBack(enabled = screen != "menu") { screen = "menu" }

        ChatScreen(
            lines, flairs, emotes, state, account,
            onSend = chat::send,
            input = chat.input,
            onIgnore = { chat.ignore(listOf(it)) },
            onHighlight = chat::highlight,
            onEmbed = { chat.watch(it); playerShown = true },
            onSearchUser = { communityArg = it; screen = "rustle" },
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
                        resolve = chat::resolve,
                    )
                }
            },
            instead = when (screen) {
                "menu" -> {
                    {
                        MainMenu(
                            host = chat.host,
                            state = state,
                            account = account,
                            live = live,
                            playerShown = playerShown,
                            picked = picked,
                            onChat = { screen = "chat" },
                            onSignIn = { screen = "signin" },
                            onSignOut = chat::signOut,
                            onWatch = { chat.watch(it); playerShown = true; screen = "chat" },
                            onTogglePlayer = { playerShown = !playerShown },
                            backgroundAudio = backgroundAudio,
                            onToggleBackgroundAudio = {
                                backgroundAudio = !backgroundAudio
                                prefs.put(BACKGROUND_AUDIO, backgroundAudio.toString())
                            },
                            onCommunity = { communityArg = null; screen = it },
                        )
                    }
                }
                "signin" -> {
                    {
                        SignInScreen(
                            origin = origin,
                            host = chat.host,
                            busy = signingIn,
                            onSession = { session, result ->
                                chat.signIn(session) { error ->
                                    result(error)
                                    if (error == null) screen = "chat"
                                }
                            },
                            onClose = { screen = "menu" },
                        )
                    }
                }
                "rustle", "polls", "dinkdonk" -> {
                    {
                        val env = CommunityEnv(
                            http = chat.http,
                            emotes = emotes,
                            flairs = flairs,
                            account = account,
                            onClose = { screen = "menu" },
                            onWatch = { chat.watch(it); playerShown = true; screen = "chat" },
                            onOpen = { target, arg -> communityArg = arg; screen = target },
                            argument = communityArg,
                        )
                        when (screen) {
                            "rustle" -> RustleSearchScreen(env)
                            "polls" -> PollArchiveScreen(env)
                            else -> DinkDonkScreen(env)
                        }
                    }
                }
                else -> null
            },
        )
    } }
}

private const val BACKGROUND_AUDIO = "background-audio"
