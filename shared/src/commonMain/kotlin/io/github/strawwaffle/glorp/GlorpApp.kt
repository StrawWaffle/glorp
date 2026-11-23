package io.github.strawwaffle.glorp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.github.strawwaffle.glorp.ui.LocalOpenLink
import io.github.strawwaffle.glorp.notify.AlertCheck
import io.github.strawwaffle.glorp.notify.NotifyControls
import io.github.strawwaffle.glorp.notify.NotifyOptions
import io.github.strawwaffle.glorp.notify.OpenRequests
import io.github.strawwaffle.glorp.community.rustleLinkOf
import io.github.strawwaffle.glorp.ui.PlayerBar
import io.github.strawwaffle.glorp.ui.SettingsScreen
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
    /** Notifications for Destiny going live, mentions and whispers; null where there are none. */
    notify: NotifyControls? = null,
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
        // Back: from anywhere to the menu, from the menu out of the app.
        OnBack(enabled = screen != "menu") { screen = "menu" }
        // Full screen while a player shows; Back leaves it first.
        var fullscreenWanted by rememberSaveable { mutableStateOf(false) }
        val fullscreen = fullscreenWanted && playerShown && embed != null
        OnBack(enabled = fullscreen) { fullscreenWanted = false }
        SystemFullscreen(fullscreen && !pictureInPicture.active)

        val settings = chat.settings
        // Notifications: the background check follows the options, wherever
        // they changed (here, or on the site for the synced ones).
        val notifyOptions = remember(chat) { NotifyOptions(prefs, settings) }
        var notifyVersion by remember { mutableStateOf(0) }
        val wantsNotifications = notifyOptions.mentions to notifyOptions.whispers
        LaunchedEffect(notify, wantsNotifications, notifyVersion) { notify?.changed() }
        // A tapped notification: the stream, a whisper conversation, or the chat around a mention.
        val openRequest by OpenRequests.pending.collectAsStateWithLifecycle()
        LaunchedEffect(openRequest) {
            val target = openRequest ?: return@LaunchedEffect
            OpenRequests.pending.value = null
            when {
                target == AlertCheck.OPEN_LIVE -> { chat.watch(null); playerShown = true; screen = "chat" }
                target.startsWith(AlertCheck.OPEN_WHISPER) -> { communityArg = target.removePrefix(AlertCheck.OPEN_WHISPER); screen = "whispers" }
                rustleLinkOf(target) != null -> { communityArg = target; screen = "rustle" }
            }
        }
        CompositionLocalProvider(
            LocalFullscreen provides Fullscreen(fullscreen) { fullscreenWanted = it },
            // rustlesearch.dev links open the app's own log search on that query.
            LocalOpenLink provides { url ->
                if (rustleLinkOf(url) == null) false
                else {
                    communityArg = url
                    screen = "rustle"
                    true
                }
            },
            LocalChatLook provides ChatLook(
                settings.showTime, settings.timestampFormat, settings.hideFlairIcons,
                settings.highlightSelf, chat.input.tags.colors, settings.taggedVisibility,
                chat.input.options.hiddenFlairs, settings.censorRemoved,
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
            player = if (!playerShown) null else {
                {
                    Bigscreen(
                        embed = embed,
                        playerUrl = embed?.let(chat::playerUrl),
                        origin = chat.playerOrigin,
                        live = live,
                        resolve = chat::resolve,
                    )
                }
            },
            bar = {
                PlayerBar(
                    embed = embed,
                    live = live,
                    picked = picked != null,
                    playerShown = playerShown,
                    menuOpen = screen != "chat",
                    unreadWhispers = if (account != null) inbox.unread else null,
                    onMenu = { screen = if (screen == "chat") "menu" else "chat" },
                    onPick = { chat.watch(it); playerShown = true },
                    onTogglePlayer = { playerShown = !playerShown },
                    onFullscreen = { fullscreenWanted = true },
                    onWhispers = { communityArg = inbox.sorted.firstOrNull { it.unread > 0 }?.nick; screen = "whispers" },
                )
            },
            instead = when (screen) {
                "menu" -> {
                    {
                        MainMenu(
                            host = chat.host,
                            state = state,
                            account = account,
                            onSignIn = { screen = "signin" },
                            onSignOut = chat::signOut,
                            onSettings = { screen = "settings" },
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
                "settings" -> {
                    {
                        SettingsScreen(
                            settings = settings,
                            synced = account != null && chat.whisperHistory,
                            backgroundAudio = backgroundAudio,
                            onBackgroundAudio = {
                                backgroundAudio = it
                                prefs.put(BACKGROUND_AUDIO, it.toString())
                            },
                            catchUp = catchUp,
                            catchUpSpeeds = catchUpSpeeds,
                            onCatchUp = {
                                catchUp = it
                                prefs.put(CATCH_UP, it.toString())
                            },
                            options = chat.input.options,
                            flairs = flairs,
                            notify = notify,
                            notifyOptions = notifyOptions,
                            onNotifyChanged = { notifyVersion++ },
                            signedInWithCookies = account != null && chat.whisperHistory,
                            mentionsService = prefs.get(NotifyOptions.SERVER) != null,
                            warnings = chat.warnings,
                            onWarnings = chat::warnAbout,
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
                            // A new link or name starts the search afresh, even with the screen open.
                            "rustle" -> key(communityArg) { RustleSearchScreen(env) }
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
