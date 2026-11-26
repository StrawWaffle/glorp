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
import io.github.strawwaffle.glorp.ui.LocalLinkTitles
import io.github.strawwaffle.glorp.ui.LocalLinkCard
import io.github.strawwaffle.glorp.ui.LocalEmoteTap
import io.github.strawwaffle.glorp.ui.LinkCard
import io.github.strawwaffle.glorp.site.LinkFlag
import io.github.strawwaffle.glorp.site.PreviewKind
import io.github.strawwaffle.glorp.site.linkFlag
import io.github.strawwaffle.glorp.site.loadLinkPreview
import io.github.strawwaffle.glorp.site.previewKind
import io.github.strawwaffle.glorp.notify.AlertCheck
import io.github.strawwaffle.glorp.notify.NotifyControls
import io.github.strawwaffle.glorp.notify.NotifyOptions
import io.github.strawwaffle.glorp.notify.OpenRequests
import io.github.strawwaffle.glorp.ui.BigscreenFollows
import io.github.strawwaffle.glorp.community.rustleLinkOf
import io.github.strawwaffle.glorp.ui.PlayerBar
import io.github.strawwaffle.glorp.ui.WhisperBanner
import io.github.strawwaffle.glorp.video.nativePlaybackPlaying
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import io.github.strawwaffle.glorp.ui.SettingsScreen
import io.github.strawwaffle.glorp.ui.UserActions
import io.github.strawwaffle.glorp.ui.Fullscreen
import io.github.strawwaffle.glorp.ui.GlorpTheme
import io.github.strawwaffle.glorp.ui.AppFont
import io.github.strawwaffle.glorp.ui.ColorTheme
import io.github.strawwaffle.glorp.site.readSignInCode
import io.github.strawwaffle.glorp.ui.Palette
import io.github.strawwaffle.glorp.ui.TagsScreen
import io.github.strawwaffle.glorp.ui.textScales
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
import io.github.strawwaffle.glorp.ui.ReportScreen
import io.github.strawwaffle.glorp.ui.CrashPrompt
import io.github.strawwaffle.glorp.diag.Diagnostics

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
    /** Problem reports and the last crash; null where there are none. */
    diagnostics: Diagnostics? = null,
    /** Scans a QR code (the computer sign-in); null where there's no scanner. */
    scanCode: (((String?) -> Unit) -> Unit)? = null,
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
    var colorTheme by remember {
        mutableStateOf((ColorTheme.entries.firstOrNull { it.name == prefs.get(COLOR_THEME) } ?: ColorTheme.Glorp).also { Palette.theme = it })
    }
    var appFont by remember { mutableStateOf(AppFont.entries.firstOrNull { it.name == prefs.get(APP_FONT) } ?: AppFont.Phone) }
    var textScale by remember { mutableStateOf(prefs.get(TEXT_SCALE)?.toFloatOrNull()?.takeIf { it in textScales } ?: 1f) }
    CompositionLocalProvider(
        LocalPictureInPicture provides pictureInPicture,
        LocalBackgroundAudio provides backgroundAudio,
        LocalCatchUp provides catchUp,
    ) { GlorpTheme(colorTheme, appFont) {
        val chat = viewModel { ChatViewModel(origin, sessions, prefs).also { vm ->
            initialEmbed?.let(Embed::parse)?.let(vm::watch)
            if (devFrames.isNotEmpty()) vm.play(devFrames)
        } }
        // The socket is held only while the app is on screen. Leaving the app
        // closes it; coming back reconnects, and the backlog chat sends on
        // connect fills the gap.
        LifecycleStartEffect(chat) {
            chat.start()
            // A stream playing on with the screen off keeps chat connected,
            // so whispers and mentions still notify at once.
            onStopOrDispose { chat.stopWhenIdle { chat.canNotifyLive() && nativePlaybackPlaying() } }
        }
        // Glorp's own settings kept the same in every Glorp and the userscript (see GlorpSync):
        // on opening, and while open whenever something changed or a few minutes have passed.
        val sync = remember(chat) {
            io.github.strawwaffle.glorp.chat.GlorpSync(
                prefs, chat.input.options,
                theme = { prefs.get(COLOR_THEME) ?: ColorTheme.Glorp.name },
                setTheme = { name ->
                    ColorTheme.entries.firstOrNull { it.name == name }?.let { colorTheme = it; Palette.theme = it; prefs.put(COLOR_THEME, it.name) }
                },
                client = io.github.strawwaffle.glorp.net.createHttpClient(),
            )
        }
        val syncScope = androidx.compose.runtime.rememberCoroutineScope()
        LifecycleStartEffect(sync) {
            val job = syncScope.launch {
                while (true) {
                    if (sync.due()) sync.sync()
                    kotlinx.coroutines.delay(20_000)
                }
            }
            onStopOrDispose { job.cancel() }
        }
        // Not read here: only chat redraws when a message comes (see ChatScreen).
        val lines = chat.lines.collectAsStateWithLifecycle()
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
        var testVersions by remember(updates) { mutableStateOf(updates?.testVersions) }
        val opening by chat.opening.collectAsStateWithLifecycle()
        // `/open nick` shows that conversation.
        LaunchedEffect(opening) {
            opening?.let { communityArg = it; screen = "whispers"; chat.conversationShown() }
        }
        var playerShown by rememberSaveable { mutableStateOf(true) }
        val embed = picked ?: live.defaultEmbed(chat.preferredPlatform)
        // Leaving the app shrinks it to the video only while there is one.
        val canShrink = playerShown && embed != null
        LaunchedEffect(canShrink) { pictureInPicture.setAvailable(canShrink) }
        // The site hears what plays here (signed in with cookies), as bigscreen tells it.
        LaunchedEffect(chat, embed, playerShown) { chat.reportWatching(if (playerShown) embed else null) }
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
                target == AlertCheck.OPEN_CHAT -> { fullscreenWanted = false; screen = "chat" }
                target.startsWith(AlertCheck.OPEN_EMBED) -> {
                    io.github.strawwaffle.glorp.site.Embed.parse(target.removePrefix(AlertCheck.OPEN_EMBED))?.let { chat.watch(it) }
                    playerShown = true
                    screen = "chat"
                }
                target.startsWith(AlertCheck.OPEN_WHISPER) -> { communityArg = target.removePrefix(AlertCheck.OPEN_WHISPER); screen = "whispers" }
                rustleLinkOf(target) != null -> { communityArg = target; screen = "rustle" }
                io.github.strawwaffle.glorp.community.polecatLinkOf(target) != null -> { communityArg = target; screen = "mentions" }
            }
        }
        // A tapped link with a preview: its card, over whatever is open.
        var linkCard by remember { mutableStateOf<LinkCardRequest?>(null) }
        linkCard?.let { card ->
            LinkCard(
                url = card.url,
                kind = card.kind,
                flag = card.flag,
                load = { loadLinkPreview(chat.http, chat.linkTitles, card.url, card.kind) },
                onWatch = { chat.watch(it); playerShown = true; screen = "chat" },
                onDismiss = { linkCard = null },
            )
        }
        // After a crash, the next start offers to report it, once.
        var crashAsked by rememberSaveable { mutableStateOf(false) }
        val crashed = remember(diagnostics) { diagnostics?.lastCrash() != null }
        if (crashed && !crashAsked && diagnostics != null) {
            CrashPrompt(
                onReport = { crashAsked = true; screen = "report" },
                onDismiss = { crashAsked = true; diagnostics.clearCrash() },
            )
        }
        // Whispers and mentions heard live. In the app, a whisper shows as a
        // banner under the bar (nothing for the conversation already open; a
        // mention is already highlighted in chat). Anywhere else, picture in
        // picture, full screen or a stream playing with the screen off, it's
        // a notification, if that kind is turned on.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var banner by remember { mutableStateOf<ChatViewModel.LiveAlert?>(null) }
        // Followed off screen too: the background check must speak up again once chat closes.
        DisposableEffect(chat, notify) {
            val job = CoroutineScope(Dispatchers.Main).launch {
                var was = false
                chat.socketState.collect {
                    val now = it == SocketState.Connected
                    if (now != was) {
                        val offScreen = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        notify?.setLive(now, catchUp = now && offScreen)
                    }
                    was = now
                }
            }
            onDispose { job.cancel(); notify?.setLive(false) }
        }
        val fullscreenNow by rememberUpdatedState(fullscreen)
        LaunchedEffect(chat) {
            chat.liveAlerts.collect { heard ->
                val inApp = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !fullscreenNow
                val whisper = heard.alert.kind == io.github.strawwaffle.glorp.notify.Alert.Kind.Whisper
                when {
                    inApp && whisper && screen == "whispers" && chat.inbox.value.open == heard.nick.lowercase() -> Unit
                    inApp && whisper -> banner = heard
                    inApp -> Unit
                    heard.notify -> notify?.show(heard.alert)
                }
            }
        }
        LaunchedEffect(banner) { if (banner != null) { delay(6_000); banner = null } }
        // Hosts, followed channels and pile-ons heard on the live socket: always a notification.
        LaunchedEffect(chat) { chat.bigscreenAlerts.collect { notify?.show(it) } }
        CompositionLocalProvider(
            LocalFullscreen provides remember(fullscreen) { Fullscreen(fullscreen) { fullscreenWanted = it } },
            LocalLinkTitles provides chat.linkTitles.takeIf { chat.input.options.linkTitles },
            LocalEmoteTap provides chat.input::openEmote,
            LocalLinkCard provides { url, text ->
                val kind = previewKind(url, settings.previewAllImages)
                if (kind == null) false else { linkCard = LinkCardRequest(url, kind, linkFlag(text)); true }
            },
            // rustlesearch.dev links open the app's own log search on that query,
            // polecat.me mentions links its Mentions screen.
            LocalOpenLink provides { url ->
                when {
                    rustleLinkOf(url) != null -> { communityArg = url; screen = "rustle"; true }
                    io.github.strawwaffle.glorp.community.polecatLinkOf(url) != null -> { communityArg = url; screen = "mentions"; true }
                    else -> false
                }
            },
            LocalChatLook provides ChatLook(
                settings.showTime, settings.timestampFormat, settings.hideFlairIcons,
                settings.highlightSelf, chat.input.tags.colors, settings.taggedVisibility,
                chat.input.options.hiddenFlairs, settings.censorRemoved,
                playing = embed?.key, otherStreams = chat.input.options.otherStreams, textScale = textScale,
                icons = chat.input.options.nameIcons, theme = colorTheme,
                animations = !settings.disableAnimations,
            ),
        ) {
        ChatScreen(
            { lines.value }, flairs, emotes, state, account,
            onSend = chat::send,
            input = chat.input,
            onEmbed = { chat.watch(it); playerShown = true },
            onSignIn = { screen = "signin" },
            onReading = { chat.readingBack = it },
            userActions = remember(chat) {
                UserActions(
                    lookUp = chat::lookUp,
                    known = chat::knownLookup,
                    profile = chat::lookUpProfile,
                    knownProfile = chat::knownProfile,
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
                    otherStreams = chat.input.options.otherStreams,
                    onOtherStreams = chat.input.options::treatOtherStreams,
                    follows = BigscreenFollows(chat.bigscreen, chat.channels) {
                        // Following asks for Android's permission the first time, and wakes the background check.
                        if (notify != null && !notify.permitted) notify.ask { notifyVersion++ } else notifyVersion++
                    },
                )
                banner?.let { heard ->
                    WhisperBanner(heard.nick, heard.text, onOpen = { banner = null; communityArg = heard.nick; screen = "whispers" }, onClose = { banner = null })
                }
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
                            emotes = emotes,
                            unreadWhispers = inbox.unread,
                            onWhispers = { communityArg = null; screen = "whispers" },
                            version = updates?.currentVersion,
                            update = update,
                            onCheckUpdate = { updates?.check(force = true) },
                            onInstallUpdate = { updates?.install(it) },
                            onReport = diagnostics?.let { { screen = "report" } },
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
                            colorTheme = colorTheme,
                            onColorTheme = { colorTheme = it; Palette.theme = it; prefs.put(COLOR_THEME, it.name) },
                            onTags = { screen = "tags" },
                            preferredPlatform = chat.preferredPlatform,
                            onPreferredPlatform = chat::preferPlatform,
                            appFont = appFont,
                            onAppFont = { appFont = it; prefs.put(APP_FONT, it.name) },
                            textScale = textScale,
                            onTextScale = { textScale = it; prefs.put(TEXT_SCALE, it.toString()) },
                            testVersions = testVersions,
                            onTestVersions = { on -> testVersions = on; updates?.testVersions = on },
                            sync = sync.takeIf { it.available },
                            scanCode = scanCode,
                        )
                    }
                }
                "report" -> diagnostics?.let { d -> { ReportScreen(d) } }
                "tags" -> {
                    { TagsScreen(chat.input.tags, chat.input.options, synced = account != null && chat.whisperHistory) }
                }
                "signin", "signin-cookies" -> {
                    {
                        val forWhispers = screen == "signin-cookies"
                        SignInScreen(
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
                            scanCode = scanCode,
                            readCode = { readSignInCode(it, chat.site) },
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
                "rustle", "polls", "dinkdonk", "mentions", "links" -> {
                    {
                        // Made again only when what's in it changes: a new one on
                        // every chat message would redraw these screens each time.
                        val env = remember(emotes, flairs, account, communityArg) { CommunityEnv(
                            http = chat.http,
                            emotes = emotes,
                            flairs = flairs,
                            account = account,
                            onClose = { screen = "menu" },
                            onWatch = { chat.watch(it); playerShown = true; screen = "chat" },
                            onOpen = { target, arg -> communityArg = arg; screen = target },
                            argument = communityArg,
                        ) }
                        when (screen) {
                            // A new link or name starts the search afresh, even with the screen open.
                            "rustle" -> key(communityArg) { RustleSearchScreen(env) }
                            "polls" -> PollArchiveScreen(env)
                            "links" -> key(communityArg) { io.github.strawwaffle.glorp.community.LinksScreen(env) }
                            "mentions" -> key(communityArg) { io.github.strawwaffle.glorp.community.MentionsScreen(env) }
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

private class LinkCardRequest(val url: String, val kind: PreviewKind, val flag: LinkFlag?)

private const val BACKGROUND_AUDIO = "background-audio"
private const val CATCH_UP = "catch-up"
private const val APP_FONT = "app-font"
private const val COLOR_THEME = "color-theme"
private const val TEXT_SCALE = "text-scale"
