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
import io.github.strawwaffle.glorp.chat.SocketState
import io.github.strawwaffle.glorp.ui.Bigscreen
import io.github.strawwaffle.glorp.ui.ChatScreen
import io.github.strawwaffle.glorp.ui.ChatLook
import io.github.strawwaffle.glorp.ui.LocalChatLook
import io.github.strawwaffle.glorp.ui.UserActions
import io.github.strawwaffle.glorp.ui.Fullscreen
import io.github.strawwaffle.glorp.ui.GlorpTheme
import io.github.strawwaffle.glorp.ui.LocalFullscreen
import io.github.strawwaffle.glorp.ui.SystemFullscreen
import io.github.strawwaffle.glorp.ui.LocalBackgroundAudio
import io.github.strawwaffle.glorp.ui.LocalCatchUp
import io.github.strawwaffle.glorp.ui.catchUpSpeeds
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
import io.github.strawwaffle.glorp.ui.WhispersScreen
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
    /** Dev builds: frames played into chat once it connects (see `MainActivity.devFrames`). */
    devFrames: List<String> = emptyList(),
    /** Glorp's own updates; null in builds that don't update themselves. */
    updates: io.github.strawwaffle.glorp.update.Updates? = null,
    pictureInPicture: PictureInPicture = PictureInPicture(),
) {
    setSingletonImageLoaderFactory { context -> glorpImageLoader(context) }
    // Keep a stream's sound going with the screen off: on unless turned off in the menu.
    var backgroundAudio by remember { mutableStateOf(prefs.get(BACKGROUND_AUDIO) != "false") }
    // Stored as the speed; older builds stored on ("true") or off ("false").
    var catchUp by remember {
        mutableStateOf(
            when (val saved = prefs.get(CATCH_UP)) {
                "false" -> 1f
                null, "true" -> 1.5f
                else -> saved.toFloatOrNull()?.takeIf { it in catchUpSpeeds } ?: 1.5f
            },
        )
    }
    CompositionLocalProvider(
        LocalPictureInPicture provides pictureInPicture,
        LocalBackgroundAudio provides backgroundAudio,
        LocalCatchUp provides catchUp,
    ) { GlorpTheme {
        val chat = viewModel { ChatViewModel(origin, sessions, prefs).also { vm ->
            initialEmbed?.let(Embed::parse)?.let(vm::watch)
            if (devFrames.isNotEmpty()) vm.play(devFrames)
        } }
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
        val poll by chat.poll.collectAsStateWithLifecycle()
        val pinned by chat.pinned.collectAsStateWithLifecycle()
        val inbox by chat.inbox.collectAsStateWithLifecycle()
        val update = updates?.state?.collectAsStateWithLifecycle()?.value
        LaunchedEffect(updates) { updates?.check() }
        val opening by chat.opening.collectAsStateWithLifecycle()
        // `/open nick` shows that conversation.
        LaunchedEffect(opening) {
            opening?.let { communityArg = it; screen = "whispers"; chat.conversationShown() }
        }
        var playerShown by rememberSaveable { mutableStateOf(true) }
        val embed = picked ?: live.defaultEmbed()
        // Leaving the app shrinks it to the video only while there is one.
        val canShrink = playerShown && embed != null
        LaunchedEffect(canShrink) { pictureInPicture.setAvailable(canShrink) }
        if (screen == "signin" && account != null) screen = "menu"
        OnBack(enabled = screen != "menu") { screen = "menu" }
        // Full screen while a player shows; Back leaves it first.
        var fullscreenWanted by rememberSaveable { mutableStateOf(false) }
        val fullscreen = fullscreenWanted && playerShown && embed != null
        OnBack(enabled = fullscreen) { fullscreenWanted = false }
        SystemFullscreen(fullscreen && !pictureInPicture.active)

        val settings = chat.settings
        CompositionLocalProvider(
            LocalFullscreen provides Fullscreen(fullscreen) { fullscreenWanted = it },
            LocalChatLook provides ChatLook(
                settings.showTime, settings.timestampFormat, settings.hideFlairIcons,
                settings.highlightSelf, chat.input.tags.colors, settings.taggedVisibility,
            ),
        ) {
        ChatScreen(
            lines, flairs, emotes, state, account,
            onSend = chat::send,
            input = chat.input,
            onEmbed = { chat.watch(it); playerShown = true },
            userActions = remember(chat) {
                UserActions(
                    lookUp = chat::lookUp,
                    recent = { communityArg = it; screen = "rustle" },
                    ignore = { chat.ignore(listOf(it)) },
                    unignore = chat::unignore,
                    highlight = chat::highlight,
                    command = { chat.send(it) },
                    watch = { chat.watch(it); playerShown = true },
                )
            },
            poll = poll,
            onVote = chat::vote,
            onClosePoll = chat::dismissPoll,
            pinned = pinned,
            onShowPin = chat::showPin,
            unreadWhispers = inbox.unread,
            onWhispers = { communityArg = inbox.sorted.firstOrNull { it.unread > 0 }?.nick; screen = "whispers" },
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
                            catchUp = catchUp,
                            onToggleCatchUp = {
                                catchUp = catchUpSpeeds[(catchUpSpeeds.indexOf(catchUp) + 1) % catchUpSpeeds.size]
                                prefs.put(CATCH_UP, catchUp.toString())
                            },
                            chatSettings = settings,
                            onCommunity = { communityArg = null; screen = it },
                            unreadWhispers = inbox.unread,
                            onWhispers = { communityArg = null; screen = "whispers" },
                            version = updates?.currentVersion,
                            update = update,
                            onCheckUpdate = { updates?.check(force = true) },
                            onInstallUpdate = { updates?.install(it) },
                        )
                    }
                }
                "signin", "signin-cookies" -> {
                    {
                        val forWhispers = screen == "signin-cookies"
                        SignInScreen(
                            startWithCookies = forWhispers,
                            origin = origin,
                            host = chat.host,
                            busy = signingIn,
                            onSession = { session, result ->
                                chat.signIn(session) { error ->
                                    result(error)
                                    if (error == null) screen = if (forWhispers) "whispers" else "chat"
                                }
                            },
                            onClose = { screen = "menu" },
                        )
                    }
                }
                "whispers" -> {
                    {
                        // Signed out (the login ran out): back to the menu.
                        val me = account
                        if (me == null) LaunchedEffect(Unit) { screen = "menu" }
                        else WhispersScreen(
                            inbox = inbox,
                            start = communityArg,
                            history = chat.whisperHistory,
                            account = me,
                            connected = state == SocketState.Connected,
                            input = chat.whisperInput,
                            onCookies = { screen = "signin-cookies" },
                            flairs = flairs,
                            emotes = emotes,
                            onOpen = chat::openConversation,
                            onClose = chat::closeConversation,
                            onRefresh = { chat.loadConversations() },
                            onSend = chat::whisper,
                            onEmbed = { chat.watch(it); playerShown = true },
                            onBack = { chat.closeConversation(); screen = "menu" },
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
        }
    } }
}

private const val BACKGROUND_AUDIO = "background-audio"
private const val CATCH_UP = "catch-up"
