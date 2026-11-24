package io.github.strawwaffle.glorp.chat

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.github.strawwaffle.glorp.diag.AppLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.auth.SessionStore
import io.github.strawwaffle.glorp.net.createHttpClient
import io.github.strawwaffle.glorp.site.Account
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.LiveSocket
import io.github.strawwaffle.glorp.site.LookBack
import io.github.strawwaffle.glorp.site.LinkTitles
import io.github.strawwaffle.glorp.site.LiveState
import io.github.strawwaffle.glorp.site.with
import io.github.strawwaffle.glorp.video.Resolution
import io.github.strawwaffle.glorp.video.Resolver
import io.github.strawwaffle.glorp.video.YouTubePlugin
import io.github.strawwaffle.glorp.video.nativePlaybackPlaying
import io.github.strawwaffle.glorp.net.createPluginHttpClient
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.Flairs
import io.github.strawwaffle.glorp.site.SessionCheck
import io.github.strawwaffle.glorp.site.SiteSettings
import io.github.strawwaffle.glorp.site.VyneerTools
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.strawwaffle.glorp.site.Site
import io.github.strawwaffle.glorp.site.checkSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Chat for the screens: survives rotation, holds the socket only while
 * [start] has been called without a matching [stop].
 */
class ChatViewModel(
    origin: String,
    private val store: SessionStore,
    private val prefs: ChatPrefs = MemoryChatPrefs(),
) : ViewModel() {
    private val client = createHttpClient()

    /** The shared client, for screens that fetch from other sites. */
    val http: io.ktor.client.HttpClient get() = client
    var site = Site.guess(origin)
        private set
    private val log = ChatLog()
    private var session: Session? = store.load()

    /** What plays here, told to the site (see [LiveSocket]); a banned embed counts as nothing, as on bigscreen. */
    private val reportedWatching = MutableStateFlow<Embed?>(null)

    fun reportWatching(embed: Embed?) {
        reportedWatching.value = embed?.takeUnless { _live.value.isBanned(it) }
    }
    private var socket: ChatSocket? = null

    // Asking the site to renew a cookie login chat no longer knows (see ChatEvent.Me).
    private var renewing = false

    /** The input's draft, history, suggestions and the ignore and highlight lists. */
    val input = ChatInputState(prefs)

    /**
     * The whisper box's input: the same suggestions as chat's, its own draft
     * and history, kept in memory only, as whispers are.
     */
    val whisperInput = ChatInputState(MemoryChatPrefs(), input.completer)
    private var whisperingTo: String? = null

    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    private val _flairs = MutableStateFlow(Flairs.None)
    val flairs: StateFlow<Flairs> = _flairs.asStateFlow()

    private val _emotes = MutableStateFlow(Emotes.None)
    val emotes: StateFlow<Emotes> = _emotes.asStateFlow()

    private val _socketState = MutableStateFlow(SocketState.Connecting)
    val socketState: StateFlow<SocketState> = _socketState.asStateFlow()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** True while a sign-in is being checked with the site. */
    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    val host: String get() = site.origin.substringAfter("://").removePrefix("www.")

    private val _live = MutableStateFlow(LiveState())
    /** What's live, hosted and watched, pushed by the site's live socket. */
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val _picked = MutableStateFlow<Embed?>(null)
    /** An embed picked by hand; null follows what bigscreen would show. */
    val picked: StateFlow<Embed?> = _picked.asStateFlow()

    private val _poll = MutableStateFlow<Poll?>(null)
    /** The poll running in chat, or the one that just ended, still shown for a moment. */
    val poll: StateFlow<Poll?> = _poll.asStateFlow()

    private val _pinned = MutableStateFlow<Pinned?>(null)
    /** The message a moderator pinned above chat. */
    val pinned: StateFlow<Pinned?> = _pinned.asStateFlow()

    /** A pinned message, and whether the reader closed it (kept per pin, as chat-gui does). */
    data class Pinned(val uuid: String, val line: ChatLine, val dismissed: Boolean)

    private var pollTimer: Job? = null

    private val _inbox = MutableStateFlow(Inbox())
    /** Whisper conversations: this session's, and the site's with a cookie login. */
    val inbox: StateFlow<Inbox> = _inbox.asStateFlow()

    private val _opening = MutableStateFlow<String?>(null)
    /** A conversation `/open` asked for, for the screens to show; taken with [conversationShown]. */
    val opening: StateFlow<String?> = _opening.asStateFlow()

    /** Whether the site's whisper history can be read: only with its cookies, not a login key. */
    val whisperHistory: Boolean get() = session is Session.Cookies

    /** Whispers sent and not yet confirmed by the server, oldest first. */
    private val unconfirmed = ArrayDeque<Pair<String, String>>()

    // Whether the last thing sent was a whisper: chat's errors don't say what
    // they answer, and "throttled" after a message isn't about a whisper.
    private var lastSentWhisper = false
    private var conversationsLoaded = false

    private fun messages(): io.github.strawwaffle.glorp.site.Messages? =
        (session as? Session.Cookies)?.let { io.github.strawwaffle.glorp.site.Messages(client, site, it) }

    /** Opens a conversation: its unread count clears and, with a cookie login, the site's history loads. */
    fun openConversation(nick: String) {
        _opening.value = null
        if (!nick.equals(whisperingTo, ignoreCase = true)) whisperInput.setDraft("")
        whisperingTo = nick
        _inbox.value = _inbox.value.opened(nick)
        val me = _account.value?.nick ?: return
        if (_inbox.value[nick]?.loaded == true) return
        val api = messages() ?: return
        viewModelScope.launch {
            val history = api.inbox(nick, me) ?: return@launch
            _inbox.value = _inbox.value.history(nick, history)
        }
    }

    /** Whispers from a conversation, leaving chat's own draft alone. */
    fun whisper(nick: String, typed: String): Boolean {
        val text = typed.trim()
        if (text.isEmpty()) return false
        val chat = socket
        when {
            _account.value == null -> notice("Sign in to whisper.")
            chat == null || _socketState.value != SocketState.Connected -> notice("Not connected. Wait for the transmission to come back.")
            else -> {
                input.replyTo = nick
                whisperInput.history.add(text)
                whisperInput.setDraft("")
                unconfirmed.addLast(nick to text)
                lastSentWhisper = true
                transmit { chat.sendWhisper(nick, text).also { if (!it) unconfirmed.removeLastOrNull() } }
                return true
            }
        }
        return false
    }

    fun closeConversation() {
        _inbox.value = _inbox.value.closed()
    }

    /** The site's conversation list, with a cookie login; at most once a minute. */
    fun loadConversations(force: Boolean = false) {
        if (conversationsLoaded && !force) return
        val api = messages() ?: return
        conversationsLoaded = true
        viewModelScope.launch {
            api.conversations()?.let { _inbox.value = _inbox.value.listed(it) }
            delay(60.seconds)
            conversationsLoaded = false
        }
    }

    fun conversationShown() {
        _opening.value = null
    }

    fun watch(embed: Embed?) {
        _picked.value = embed
    }

    private val youtube = YouTubePlugin(client, ::createPluginHttpClient, viewModelScope)
    private val resolver = Resolver(client, youtube)

    /** Looks up a stream the app's own player can play, or says to use the web player. */
    suspend fun resolve(embed: Embed): Resolution = resolver.resolve(embed)

    /** The platform player's address for [embed], or null for a platform the site doesn't embed. */
    fun playerUrl(embed: Embed): String? = embed.playerUrl(site)

    /** The page the player is embedded in, as far as the platforms can tell: the site itself. */
    val playerOrigin: String get() = site.origin

    // A busy chat sends several frames a second. They queue here and are
    // applied in batches, so the list is redrawn a few times a second at most
    // instead of once per message. Notices from the app go the same way, so
    // only one coroutine ever touches the log.
    // Full, the socket waits rather than anything being dropped: the oldest
    // could be who you are, the backlog, or a mute.
    private val events = Channel<Any>(capacity = 2_000)
    private var connection: Job? = null
    private var wanted = false
    private val ready = CompletableDeferred<Unit>()
    private val emotesLoaded = CompletableDeferred<Unit>()
    private val combo: (ChatLine) -> String? = { soleEmote(it, _emotes.value)?.prefix }

    init {
        settings.onChange = ::settingsChanged
        // The background notification check runs without this view model:
        // it reads the site and who is signed in from here.
        prefs.put(io.github.strawwaffle.glorp.notify.NotifyOptions.ORIGIN, origin)
        // Not cleared while the account isn't known yet (offline at launch):
        // only signing out forgets the name.
        viewModelScope.launch {
            _account.collect { if (it != null || session == null) prefs.put(io.github.strawwaffle.glorp.notify.NotifyOptions.NICK, it?.nick) }
        }
        viewModelScope.launch {
            site = Site.discover(client, origin)
            launch { _flairs.value = Flairs.fetch(client, site) }
            launch { io.github.strawwaffle.glorp.ui.ChatRendering.comboSplat = comboSplat(site) }
            launch {
                // Chat starts with each emote's own rules; how emotes look beside others follows.
                val emoteSite = io.github.strawwaffle.glorp.ui.ChatRendering.emoteCdn?.let { site.copy(cdn = it) } ?: site
                _emotes.value = Emotes.fetch(io.github.strawwaffle.glorp.net.createCachingHttpClient(), emoteSite) { first -> _emotes.value = first; emotesLoaded.complete(Unit) }
                emotesLoaded.complete(Unit)
            }
            // Like chat-gui on page load: ask who the cookies belong to first,
            // which also renews `sid` from `rememberme` when it has run out.
            // A login key can only be checked by chat itself, on connect.
            (session as? Session.Cookies)?.let { refresh(it) }
            ready.complete(Unit)
        }
        viewModelScope.launch { applyEvents() }
        // What autocomplete offers depends on who is signed in: the emotes
        // their tier allows, the commands their powers allow.
        viewModelScope.launch {
            combine(_emotes, _account) { e, a -> e to a }.collect { (emotes, account) ->
                val features = account?.features.orEmpty()
                input.completer.setEmotes(emotes.all.filter { mayUse(it, features, account?.subTier ?: 0, account?.roles.orEmpty()) }.map { it.prefix })
                input.completer.setCommands(commandWords(account?.modPowers == true))
            }
        }
    }

    fun start() {
        backgroundWatch?.cancel()
        backgroundWatch = null
        inBackground = false
        // What arrived while chat was off screen, shown at once.
        _lines.value = log.snapshot()
        wanted = true
        watchMods()
        if (connection?.isActive == true) { startLive(); return }
        connection = viewModelScope.launch {
            ready.await()
            fetchEmotesAgain()
            // Chat and the live feed connect while the emote list loads; the
            // event loop waits for the list before it groups the backlog.
            startLive()
            val chat = ChatSocket(client, site.chatSocketUrl, session?.cookieHeader)
            socket = chat
            launch {
                chat.state.collect {
                    // Dropped: chat told of everything until now; the background check takes it from here.
                    // Whispers sent and not confirmed by then never will be.
                    if (it != SocketState.Connected) unconfirmed.clear()
                    if (_socketState.value == SocketState.Connected && it != SocketState.Connected && _account.value != null) {
                        io.github.strawwaffle.glorp.notify.AlertCheck.heardUntilNow(prefs, socket?.lastHeardMs ?: 0)
                    }
                    _socketState.value = it
                }
            }
            // Frames are read and parsed off the main thread (a backlog or the
            // user list on connect is a lot of text, and busy chat is hundreds
            // of frames a second); only applying them happens on it.
            val now = { Clock.System.now().toEpochMilliseconds() }
            withContext(Dispatchers.Default) {
                chat.run { raw ->
                    val event = parseFrame(raw, now)
                    events.send(Parsed(event, (event as? ChatEvent.Names)?.let { rosterNicks(it.payload) }))
                }
            }
        }
    }

    private var emotesAgain: Job? = null

    // The list failed to load at launch (a bad connection): try again each time
    // chat comes back, rather than showing emotes as words all session.
    private fun fetchEmotesAgain() {
        if (!emotesLoaded.isCompleted || _emotes.value.all.isNotEmpty() || emotesAgain?.isActive == true) return
        emotesAgain = viewModelScope.launch {
            val fetched = Emotes.fetch(client, site)
            if (fetched.all.isNotEmpty()) _emotes.value = fetched
        }
    }

    /** Where Destiny's stream plays when he's live on several platforms (see [LiveState.defaultEmbed]). */
    var preferredPlatform by mutableStateOf(prefs.get(PREFERRED_PLATFORM) ?: LiveState.DEFAULT_PLATFORM)
        private set

    fun preferPlatform(platform: String) {
        preferredPlatform = platform
        prefs.put(PREFERRED_PLATFORM, platform)
    }

    /** Whether nukes, mutelinks and banned phrases are fetched to warn the input (on unless turned off). */
    var warnings by mutableStateOf(prefs.get(WARNINGS) != "false")
        private set

    private var watching: Job? = null

    fun warnAbout(on: Boolean) {
        warnings = on
        prefs.put(WARNINGS, on.toString())
        if (on && wanted) watchMods()
        if (!on) {
            watching?.cancel()
            watching = null
            input.watch.nukes = emptyList()
            input.watch.phrases = emptyList()
            input.watch.mutelinks = null
        }
    }

    private val tools = VyneerTools(client)

    // Only someone who can type is warned; nobody is, with chat in the background.
    private fun watchMods() {
        if (!warnings || inBackground || _account.value == null || watching?.isActive == true) return
        watching = viewModelScope.launch { tools.run(input.watch) }
    }

    fun stop() {
        AppLog.note("chat", "closed: the app left the screen")
        if (_account.value != null && _socketState.value == SocketState.Connected) {
            io.github.strawwaffle.glorp.notify.AlertCheck.heardUntilNow(prefs, socket?.lastHeardMs ?: 0)
        }
        wanted = false
        liveJob?.cancel()
        liveJob = null
        watching?.cancel()
        watching = null
        connection?.cancel()
        connection = null
        socket = null
        _socketState.value = SocketState.Waiting
    }

    private fun reconnect() {
        if (!wanted) return
        val background = inBackground
        stop()
        start()
        // Off screen (a sign-in finishing after the app was left): stay off screen.
        if (background) stopWhenIdle { canNotifyLive() && nativePlaybackPlaying() }
    }

    /** A login key being tried: chat's ME answer decides, then [onResult] hears. */
    private class Pending(val key: Session.LoginKey, val previous: Session?, val onResult: (String?) -> Unit)
    private var pending: Pending? = null
    private var pendingTimeout: Job? = null

    /**
     * Signs in. Cookies are checked with the site first; a login key can only
     * be checked by connecting to chat with it and reading who chat says we
     * are, so the connection is swapped over and swapped back if it fails.
     */
    fun signIn(candidate: Session, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            ready.await()
            when (candidate) {
                is Session.Cookies -> {
                    _signingIn.value = true
                    val result = checkSession(client, site, candidate)
                    _signingIn.value = false
                    when (result) {
                        is SessionCheck.Valid -> {
                            adopt(result.session, result.account)
                            notice("Signed in as ${result.account.nick}.")
                            reconnect()
                            onResult(null)
                        }
                        SessionCheck.Invalid -> onResult("$host doesn't recognise those cookies. They may have expired.")
                        SessionCheck.Unreachable -> onResult("Couldn't reach $host. Check the connection and try again.")
                    }
                }
                is Session.LoginKey -> {
                    _signingIn.value = true
                    pending = Pending(candidate, session, onResult)
                    session = candidate
                    reconnect()
                    pendingTimeout = launch {
                        delay(20.seconds)
                        finishPending(null, "Chat didn't answer. Check the connection and try again.")
                    }
                }
            }
        }
    }

    private fun finishPending(account: Account?, error: String?) {
        val attempt = pending ?: return
        pending = null
        pendingTimeout?.cancel()
        _signingIn.value = false
        if (account != null) {
            adopt(attempt.key, account)
            notice("Signed in as ${account.nick}.")
            attempt.onResult(null)
        } else {
            session = attempt.previous
            reconnect()
            attempt.onResult(error)
        }
    }

    fun signOut() {
        settingsLoadedFor = null
        settingsUpload?.cancel()
        session = null
        store.save(null)
        _account.value = null
        notice("Signed out.")
        reconnect()
    }

    /**
     * Sends what was typed, or does what the command asks, as chat-gui does:
     * the commands it handles itself are handled here, the ones it sends to
     * the server go the same way, and the input is cleared (or, for `/r`,
     * refilled) once it's done. A message that can't go now stays in the
     * input. Returns whether it was taken.
     */
    fun send(typed: String): Boolean {
        val text = typed.trim()
        if (text.isEmpty()) return false
        val me = _account.value
        if (me == null) {
            notice("Sign in to chat.")
            return false
        }
        val command = parseCommand(text, me.modPowers)
        val running = _poll.value?.takeIf { !it.ended }
        // A number while a poll runs is a vote, as in chat-gui.
        if (command is Command.Say && running != null && running.isVote(text)) {
            input.setDraft("")
            vote(text.toInt())
            return true
        }
        val needsChat = command is Command.Say || command is Command.Whisper || command is Command.Send ||
            command is Command.PostEmbed || command is Command.StartPoll || command is Command.StopPoll
        val chat = socket
        if (needsChat && (chat == null || _socketState.value != SocketState.Connected)) {
            notice("Not connected. Wait for the transmission to come back.")
            return false
        }
        input.history.add(text)
        input.setDraft("")
        when (command) {
            is Command.Say -> {
                lastSentWhisper = false
                transmit { chat!!.sendMessage(command.text) }
            }
            is Command.Whisper -> {
                if (command.nick.equals(me.nick, ignoreCase = true)) {
                    notice("Cannot send a message to yourself")
                } else {
                    input.replyTo = command.nick
                    // Shown once the server takes it (PRIVMSGSENT): it can
                    // refuse, for an account too new or a name that's wrong.
                    unconfirmed.addLast(command.nick to command.text)
                    lastSentWhisper = true
                    transmit {
                        chat!!.sendWhisper(command.nick, command.text).also { if (!it) unconfirmed.removeLastOrNull() }
                    }
                }
            }
            is Command.Send -> transmit { chat!!.sendEvent(command.event, command.payload) }
            is Command.PostEmbed -> {
                val first = command.args.firstOrNull()?.let(Embed::parse)
                val embed = first ?: _picked.value ?: _live.value.defaultEmbed(preferredPlatform)
                val words = if (first != null) command.args.drop(1) else command.args
                if (embed == null) notice("Nothing embedded")
                else {
                    lastSentWhisper = false
                    transmit { chat!!.sendMessage(("#${embed.key} " + words.joinToString(" ")).trim()) }
                }
            }
            Command.Reply -> {
                val to = input.replyTo
                if (to == null) notice("No one to reply to. :(") else input.setDraft("/w $to ")
            }
            is Command.Ignore -> ignore(command.nicks, me.nick)
            is Command.Unignore -> {
                input.ignored.remove(command.nicks)
                notice("${command.nicks.joinToString(", ")} ${if (command.nicks.size == 1) "has" else "have"} been removed from your ignore list")
            }
            Command.UnignoreAll -> {
                input.ignored.clear()
                notice("Your ignore list has been cleared.")
            }
            is Command.Highlight -> highlight(command.nick, command.on)
            is Command.Tag -> tag(command.nick, command.args, me.nick)
            is Command.Untag -> if (command.nick == null) {
                notice(tagList(input.tags.colors, input.tags.notes))
                notice("Usage. /untag <nick>")
            } else {
                untag(command.nick)
            }
            Command.UntagAll -> {
                input.tags.clear()
                notice("Your tags have been cleared.")
            }
            is Command.TimestampFormat -> if (command.format == null) {
                notice("Current format: ${settings.timestampFormat} (the default is 'HH:mm', for more info: http://momentjs.com/docs/#/displaying/format/)")
            } else {
                settings.set("timestampformat", kotlinx.serialization.json.JsonPrimitive(command.format))
                notice("New format: ${command.format}.")
            }
            Command.Help -> notice(helpText(me.modPowers))
            is Command.Stalk -> lookBack(command.nick ?: me.nick, stalk = true, command.limit)
            is Command.Mentions -> lookBack(command.nick ?: me.nick, stalk = false, command.limit)
            is Command.MaxLines -> if (command.lines == null) {
                notice("Maximum lines stored: ${settings.maxLines}.")
            } else {
                settings.set("maxlines", kotlinx.serialization.json.JsonPrimitive(command.lines))
                notice("Set maximum lines to ${settings.maxLines}.")
            }
            Command.EmoteList -> notice("Available emotes: ${_emotes.value.all.joinToString(", ") { it.prefix }}.")
            is Command.Watch -> {
                val embed = Embed.parse(command.link)
                if (embed == null) notice("Usage: /embed <link> OR /e <link> (bigscreen links and #platform/id)")
                else watch(embed)
            }
            is Command.StartPoll -> {
                val request = command.request
                when {
                    request == null -> notice(
                        "Usage: /${command.name} [--hidden] [--weighted] <question>? <option 1> or <option 2> " +
                            "[or <option 3> [or <option 4> ... [or <option n>]]] [<time>].",
                    )
                    running != null -> notice("Poll already started.")
                    !mayRunPolls(me.features + me.roles) -> notice("You do not have permission to start a poll.")
                    else -> transmit { chat!!.sendEvent("STARTPOLL", pollPayload(request)) }
                }
            }
            Command.StopPoll -> when {
                running == null -> notice("No poll started.")
                !mayRunPolls(me.features + me.roles) -> notice("You do not have permission to stop this poll.")
                else -> transmit { chat!!.sendEvent("STOPPOLL", JsonObject(emptyMap())) }
            }
            Command.ShowPoll -> {
                val last = _poll.value
                if (last == null) notice("There hasn't been a poll yet.") else _poll.value = last.copy(dismissed = false)
            }
            is Command.Open -> {
                if (command.nick.equals(me.nick, ignoreCase = true)) notice("Cannot send a message to yourself")
                else _opening.value = command.nick
            }
            is Command.Problem -> notice(command.text)
        }
        return true
    }

    /**
     * Dev builds: plays [frames] into chat as if the server sent them, once
     * connected. `sleep N` waits N ms; `${'$'}NOW` becomes the time.
     */
    fun play(frames: List<String>) {
        viewModelScope.launch {
            _socketState.first { it == SocketState.Connected }
            delay(2.seconds)
            val started = Clock.System.now().toEpochMilliseconds()
            var sent = 0
            for (frame in frames) {
                val pause = frame.removePrefix("sleep ").takeIf { it != frame }?.trim()?.toLongOrNull()
                if (pause != null) delay(pause.milliseconds)
                else {
                    // Parsed off the main thread, as frames from the socket are.
                    val raw = frame.replace("${'$'}NOW", Clock.System.now().toEpochMilliseconds().toString())
                    events.send(withContext(Dispatchers.Default) { Parsed(parseFrame(raw) { Clock.System.now().toEpochMilliseconds() }, null) })
                    sent++
                }
            }
            val took = Clock.System.now().toEpochMilliseconds() - started
            AppLog.note("chat", "replayed $sent frames in ${took / 1000.0} s (${if (took > 0) sent * 1000 / took else sent}/s)")
        }
    }

    /** Votes for [option] (from 1) in the running poll, by tapping it or typing its number. */
    fun vote(option: Int) {
        val current = _poll.value ?: return
        if (current.ended) return
        val chat = socket
        when {
            _account.value == null -> notice("Sign in to vote.")
            !current.canVote -> notice("You have already voted!")
            chat == null || _socketState.value != SocketState.Connected -> notice("Not connected. Wait for the transmission to come back.")
            else -> {
                notice("Your vote has been cast!")
                // Until the server counts it, so a second tap doesn't vote twice.
                _poll.value = current.copy(canVote = false)
                transmit { chat.sendEvent("CASTVOTE", buildJsonObject { put("vote", JsonPrimitive("$option")) }) }
            }
        }
    }

    /** Closes the poll; `/showpoll` brings it back. */
    fun dismissPoll() {
        _poll.value = _poll.value?.copy(dismissed = true)
    }

    /** Closes or reopens the pinned message. Closing is remembered for that pin. */
    fun showPin(shown: Boolean) {
        val pin = _pinned.value ?: return
        _pinned.value = pin.copy(dismissed = !shown)
        val closed = prefs.getList(PINS_CLOSED).filter { it != pin.uuid }
        prefs.putList(PINS_CLOSED, if (shown) closed else (closed + pin.uuid).takeLast(20))
    }

    /** Tapping a name and choosing Ignore, or `/ignore`. Empty [nicks] lists who is ignored. */
    fun ignore(nicks: List<String>, self: String? = _account.value?.nick) {
        if (nicks.isEmpty()) {
            val list = input.ignored.nicks
            notice(if (list.isEmpty()) "Your ignore list is empty." else "Ignoring the following people: ${list.sorted().joinToString(", ")}.")
            return
        }
        if (nicks.any { it.equals(self, ignoreCase = true) }) {
            notice("You can't add yourself to your ignore list.")
            return
        }
        input.ignored.add(nicks)
        // Their lines go now; new ones are dropped as they come (see apply).
        events.trySend(Forget(nicks))
        notice(if (nicks.size == 1) "Ignoring ${nicks[0]}" else "Added the following people to your ignore list: ${nicks.joinToString(", ")}")
    }

    /** The site's details about [nick], for the name sheet. */
    /** The site's details about [nick], kept a few minutes so their sheet opens with them at once next time. */
    suspend fun lookUp(nick: String): io.github.strawwaffle.glorp.site.UserLookup {
        val key = nick.lowercase()
        val now = Clock.System.now().toEpochMilliseconds()
        lookups[key]?.takeIf { now - it.second < 5 * 60_000 }?.let { return it.first }
        val found = io.github.strawwaffle.glorp.site.lookUpUser(client, site, nick)
        if (found !is io.github.strawwaffle.glorp.site.UserLookup.Failed) {
            lookups[key] = found to now
            while (lookups.size > 100) lookups.remove(lookups.keys.first())
        }
        return found
    }

    /** Peeks at what [lookUp] keeps, for a sheet to start from. */
    fun knownLookup(nick: String): io.github.strawwaffle.glorp.site.UserLookup? =
        lookups[nick.lowercase()]?.takeIf { Clock.System.now().toEpochMilliseconds() - it.second < 5 * 60_000 }?.first

    private val lookups = LinkedHashMap<String, Pair<io.github.strawwaffle.glorp.site.UserLookup, Long>>()

    fun unignore(nick: String) {
        input.ignored.remove(listOf(nick))
        notice("$nick has been removed from your ignore list")
    }

    /** `/highlight nick`, `/unhighlight nick`, or the list for a null [nick]. */
    fun highlight(nick: String?, on: Boolean) {
        if (nick == null) {
            val list = input.highlighted.nicks
            notice(if (list.isEmpty()) "No highlighted users." else "Currently highlighted users: ${list.sorted().joinToString(",")}.")
            return
        }
        val lower = nick.lowercase()
        if (on) input.highlighted.add(listOf(lower)) else input.highlighted.remove(listOf(lower))
        notice(if (on) "Highlighting $lower." else "No longer highlighting $lower.")
    }

    /** `/tag`, as chat-gui does it: the list, one tag, or a new or changed tag. */
    private fun tag(nick: String?, args: List<String>, me: String) {
        val usage = "Usage. /tag <nick> [<color>, <note>]\n(Available colors:${TAG_COLORS.joinToString(", ")})"
        val tags = input.tags
        if (nick == null) {
            notice(tagList(tags.colors, tags.notes))
            notice(usage)
            return
        }
        val n = nick.lowercase()
        val color = tags.colors[n]
        when {
            n == me.lowercase() -> notice("Cannot tag yourself")
            !input.completer.knows(n) -> notice("User must be present in chat to tag.")
            color != null && args.isEmpty() -> {
                notice("$n ($color) ${tags.notes[n].orEmpty()}".trimEnd())
                notice(usage)
            }
            else -> {
                val (newColor, note) = readTag(args, tags.notes[n].orEmpty()) { TAG_COLORS.random() }
                tags.tag(n, newColor, note)
                notice("Tagged $nick as $newColor.")
            }
        }
    }

    private fun untag(nick: String) {
        input.tags.untag(nick)
        notice("Un-tagged ${nick.lowercase()}.")
    }

    private fun transmit(action: suspend () -> Boolean) {
        viewModelScope.launch { if (!action()) notice("Couldn't send. The connection dropped.") }
    }

    /** A frame read on a background thread, with the names in it when it's the user list. */
    private class Parsed(val event: ChatEvent?, val roster: List<String>?)

    /** Asks the event loop to take an ignored user's lines out. */
    private class Forget(val nicks: List<String>)

    private suspend fun refresh(current: Session.Cookies) {
        when (val result = checkSession(client, site, current)) {
            is SessionCheck.Valid -> adopt(result.session, result.account)
            SessionCheck.Invalid -> {
                session = null
                store.save(null)
                notice("Your login on $host has expired. Sign in again to chat.")
            }
            // Keep it: offline at launch shouldn't sign anyone out.
            SessionCheck.Unreachable -> Unit
        }
    }

    private fun adopt(newSession: Session, account: Account) {
        session = newSession
        store.save(newSession)
        _account.value = account
        val userId = account.userId
        if (newSession is Session.Cookies && userId != null) loadSettings(newSession, userId)
    }

    /** Chat's settings: the input's, kept with the account when signed in with cookies. */
    val settings: ChatSettings get() = input.settings

    /** The user whose account copy of the settings has been loaded; uploads wait for it. */
    private var settingsLoadedFor: String? = null
    private var settingsUpload: Job? = null
    private var settingsChange = 0

    /**
     * The account's copy of the settings, resolved against this device's as
     * chat-gui does (see [resolveSettings]): on a first sync the two are
     * merged; after that the account's copy wins unless this device has
     * changes it hasn't sent yet.
     */
    private fun loadSettings(cookies: Session.Cookies, userId: String) {
        settingsLoadedFor = null
        viewModelScope.launch {
            val server = SiteSettings(client, site, cookies).load() ?: return@launch
            if (_account.value?.userId != userId) return@launch
            val resolved = resolveSettings(
                server,
                settings.values,
                userId,
                syncedFor = prefs.get(SETTINGS_SYNCED),
                pending = prefs.get(SETTINGS_PENDING) == userId,
            )
            if (!resolved.upload) prefs.put(SETTINGS_PENDING, null)
            settings.replace(resolved.settings)
            prefs.put(SETTINGS_SYNCED, userId)
            settingsLoadedFor = userId
            if (resolved.upload) uploadSettings()
        }
    }

    /** A setting changed here: mark it unsent for this user, and send it a second after the last change. */
    private fun settingsChanged() {
        val userId = _account.value?.userId ?: return
        if (session !is Session.Cookies) return
        prefs.put(SETTINGS_PENDING, userId)
        uploadSettings()
    }

    private fun uploadSettings() {
        val cookies = session as? Session.Cookies ?: return
        val userId = _account.value?.userId ?: return
        if (settingsLoadedFor != userId) return
        val change = ++settingsChange
        settingsUpload?.cancel()
        settingsUpload = viewModelScope.launch {
            delay(1.seconds)
            val sent = SiteSettings(client, site, cookies).save(settings.full())
            // Unless a newer change is on its way.
            if (sent && change == settingsChange) prefs.put(SETTINGS_PENDING, null)
        }
    }

    /** Titles for YouTube and Rumble links in chat. */
    val linkTitles = LinkTitles(client, viewModelScope)

    private var lookingBack: Job? = null

    /** `/stalk` and `/mentions`, as chat-gui shows them: a line saying when, then the messages. */
    private fun lookBack(nick: String, stalk: Boolean, limit: Int) {
        if (lookingBack?.isActive == true) {
            notice(if (stalk) "Still busy stalking $nick ..." else "Still busy getting $nick's mentions")
            return
        }
        notice(if (stalk) "Getting messages from $nick ..." else "Getting mentions for $nick ...")
        val look = LookBack(client, site, session as? Session.Cookies, prefs.get(io.github.strawwaffle.glorp.notify.NotifyOptions.SERVER))
        lookingBack = viewModelScope.launch {
            when (val found = if (stalk) look.stalk(nick, limit) else look.mentions(nick, limit)) {
                is LookBack.Result.Failed -> notice(found.why)
                is LookBack.Result.Lines -> if (found.lines.isEmpty()) {
                    notice(if (stalk) "No messages from $nick." else "No mentions for $nick.")
                } else {
                    val last = io.github.strawwaffle.glorp.community.localDateTime(found.lines.last().at / 1000)
                    val from = found.source?.let { " (from $it)" }.orEmpty()
                    notice(if (stalk) "Stalked $nick last seen $last$from." else "Mentions for $nick last seen $last$from.")
                    found.lines.forEach { events.trySend(ChatLine.user(it.nick, emptyList(), it.text, it.at, subTier = Int.MAX_VALUE)) }
                }
            }
        }
    }

    private fun notice(text: String) {
        events.trySend(ChatLine.info(text, Clock.System.now().toEpochMilliseconds()))
    }

    private suspend fun applyEvents() {
        val now = { Clock.System.now().toEpochMilliseconds() }
        var first = true
        while (true) {
            val item = events.receive()
            // The backlog needs the emote list to group combos; don't wait long for it.
            if (first) {
                withTimeoutOrNull(5.seconds) { emotesLoaded.await() }
                first = false
            }
            var changed = apply(item, now)
            while (true) {
                val next = events.tryReceive().getOrNull() ?: break
                changed = apply(next, now) or changed
            }
            // `maxlines` may have changed here or on the site.
            settings.maxLines.let { if (it != log.capacity) { log.capacity = it; changed = true } }
            // Back at the bottom after reading: down to the usual number again.
            if (log.holding != readingBack) { log.holding = readingBack; changed = true }
            if (changed && !inBackground) {
                val snapshot = log.snapshot()
                // The screen's rows for the newest lines, prepared and measured
                // here off the main thread, so its frames don't have to.
                LinePrefetch.hook?.let { prefetch -> withContext(Dispatchers.Default) { runCatching { prefetch(snapshot) } } }
                _lines.value = snapshot
            }
            // Reading back, new lines land out of view: once a second is
            // plenty, but back at the bottom it's at once. (Bigger batches in
            // a burst were tried: on a phone they made the slow frames slower.)
            if (readingBack) {
                var waited = 0.milliseconds
                while (readingBack && waited < READING_INTERVAL) { delay(publishInterval); waited += publishInterval }
            } else {
                delay(publishInterval)
            }
        }
    }

    private suspend fun apply(item: Any, now: () -> Long): Boolean {
        if (item is ChatLine) return log.add(item)
        if (item is Forget) return item.nicks.fold(false) { changed, nick -> log.removeNick(nick) or changed }
        val parsed = item as? Parsed ?: Parsed(parseFrame(item as String, now), null)
        return when (val event = parsed.event) {
            is ChatEvent.Message -> received(event.line).also { if (it) alertFor(event.line) }
            is ChatEvent.History -> {
                val added = event.lines.fold(false) { changed, line -> received(line) or changed }
                val censored = event.removed.fold(false) { changed, nick -> log.moderate(nick) or changed }
                // Spotlights sent on connect may name messages the backlog just brought.
                val marked = log.respotlight()
                added or censored or marked
            }
            is ChatEvent.Joined -> { present.add(event.nick.lowercase()); false }
            is ChatEvent.Left -> { present.remove(event.nick.lowercase()); false }
            is ChatEvent.Names -> {
                present.clear()
                (parsed.roster ?: rosterNicks(event.payload)).forEach { present.add(it.lowercase()) }
                // Only someone who can type needs names to complete.
                if (_account.value != null) input.completer.setRoster(parsed.roster ?: rosterNicks(event.payload))
                false
            }
            is ChatEvent.Moderation -> {
                val censored = event.removed?.let(log::moderate) ?: false
                log.add(event.notice) or censored
            }
            is ChatEvent.Spotlight -> log.spotlight(event.spot)
            is ChatEvent.Unspotlight -> log.unspotlight(event.key)
            is ChatEvent.Spotlights -> log.setSpotlights(event.spots)
            is ChatEvent.Notice -> log.add(ChatLine.info(event.text, now()))
            is ChatEvent.Me -> {
                val me = event.account
                when {
                    pending != null -> {
                        if (me != null) finishPending(me, null)
                        else finishPending(null, "Chat didn't accept that key. Copy it again from the developer page.")
                        false
                    }
                    me != null -> {
                        watchMods()
                        // Chat doesn't say the user id; the site's pages did.
                        val known = _account.value
                        _account.value = if (known != null && known.nick.equals(me.nick, ignoreCase = true)) me.copy(userId = known.userId) else me
                        loadConversations()
                        false
                    }
                    session is Session.Cookies && !renewing -> {
                        // `sid` may simply have run out while the app ran:
                        // `rememberme` can get a new one, as at launch. Only
                        // the site saying no signs out.
                        val stale = session as Session.Cookies
                        renewing = true
                        viewModelScope.launch {
                            try {
                                when (val result = checkSession(client, site, stale)) {
                                    // A new `sid`: connect with it. The same one the site
                                    // accepts and chat doesn't: nothing to fix from here.
                                    is SessionCheck.Valid -> if (result.session != stale) {
                                        adopt(result.session, result.account)
                                        reconnect()
                                    }
                                    SessionCheck.Invalid -> if (session == stale) {
                                        session = null
                                        store.save(null)
                                        _account.value = null
                                        log.add(ChatLine.info("Your login on $host no longer works. Sign in again to chat.", now()))
                                        _lines.value = log.snapshot()
                                    }
                                    SessionCheck.Unreachable -> Unit
                                }
                            } finally {
                                renewing = false
                            }
                        }
                        false
                    }
                    session is Session.Cookies -> false
                    session != null -> {
                        // Chat doesn't know the stored login any more: a key
                        // removed on the site.
                        session = null
                        store.save(null)
                        _account.value = null
                        log.add(ChatLine.info("Your login on $host no longer works. Sign in again to chat.", now()))
                    }
                    else -> false
                }
            }
            is ChatEvent.PollStart -> {
                val poll = event.poll
                val known = _poll.value
                // Sent again on every reconnect: the same poll keeps how it was shown.
                val same = known != null && !known.ended && known.question == poll.question && known.options == poll.options
                _poll.value = if (same) poll.copy(dismissed = known!!.dismissed) else poll
                schedulePollEnd(poll.endsAt + POLL_GRACE_MS - now())
                if (!same) log.add(ChatLine.info(poll.startText(), now())) else false
            }
            is ChatEvent.PollStop -> {
                val running = _poll.value?.takeIf { !it.ended } ?: return false
                val results = event.totals?.takeIf { running.hidden && it.size == running.options.size }
                val ended = if (results != null) running.copy(hidden = false, totals = results, votes = event.votes ?: running.votes, ended = true)
                else running.copy(ended = true)
                _poll.value = ended
                schedulePollEnd(Poll.END_SHOWN_MS)
                log.add(ChatLine.info(ended.endText(), now()))
            }
            is ChatEvent.VoteCast -> {
                _poll.value = _poll.value?.takeIf { !it.ended }?.cast(event.option, event.quantity) ?: return false
                false
            }
            is ChatEvent.VoteCounted -> {
                _poll.value = _poll.value?.copy(myVote = event.option, canVote = false)
                false
            }
            is ChatEvent.Pin -> {
                val line = event.line
                _pinned.value = if (line == null) null
                else Pinned(event.uuid, line, dismissed = event.uuid in prefs.getList(PINS_CLOSED))
                false
            }
            ChatEvent.WhisperSent -> {
                val (nick, text) = unconfirmed.removeFirstOrNull() ?: return false
                val me = _account.value?.nick.orEmpty()
                _inbox.value = _inbox.value.sent(nick, me, text, now())
                // In chat too, unless the conversation is on screen.
                if (_inbox.value.open == nick.lowercase()) false
                else log.add(ChatLine.info("Whisper to $nick: $text", now()))
            }
            is ChatEvent.Error -> {
                if (lastSentWhisper && unconfirmed.isNotEmpty()) {
                    unconfirmed.removeFirstOrNull()?.let { (nick, _) -> _inbox.value = _inbox.value.refused(nick, errorText(event.code)) }
                }
                if (isFatal(event.code)) socket?.halt()
                log.add(ChatLine.info(errorText(event.code), now()))
            }
            null -> false
        }
    }

    /** A whisper, or a message mentioning you, as it arrives over the live connection. */
    data class LiveAlert(val alert: io.github.strawwaffle.glorp.notify.Alert, val nick: String, val text: String, val notify: Boolean)

    private val _liveAlerts = kotlinx.coroutines.flow.MutableSharedFlow<LiveAlert>(extraBufferCapacity = 16)

    /**
     * Whispers and mentions as chat hears them, for a notification right away
     * (picture in picture, full screen, the app in the background with a
     * stream playing) or the banner in the app. [LiveAlert.notify] says
     * whether that kind of notification is on.
     */
    val liveAlerts: kotlinx.coroutines.flow.SharedFlow<LiveAlert> = _liveAlerts

    private fun alertFor(line: ChatLine) {
        val nick = line.nick ?: return
        val me = _account.value?.nick ?: return
        if (nick.equals(me, ignoreCase = true)) return
        // Read only for a message that is a whisper or a mention, not for every one.
        val options by lazy { io.github.strawwaffle.glorp.notify.NotifyOptions(prefs, settings) }
        val alert = when {
            line.kind == ChatLine.Kind.Whisper -> io.github.strawwaffle.glorp.notify.Alert(
                io.github.strawwaffle.glorp.notify.Alert.Kind.Whisper, "Whisper from $nick", line.text,
                io.github.strawwaffle.glorp.notify.AlertCheck.OPEN_WHISPER + nick,
            ).let { LiveAlert(it, nick, line.text, options.whispers) }
            line.kind == ChatLine.Kind.User && io.github.strawwaffle.glorp.ui.mentions(line.text, me) -> io.github.strawwaffle.glorp.notify.Alert(
                io.github.strawwaffle.glorp.notify.Alert.Kind.Mention, "$nick mentioned you", line.text,
                io.github.strawwaffle.glorp.notify.AlertCheck.OPEN_CHAT,
            ).let { LiveAlert(it, nick, line.text, options.mentions) }
            else -> return
        }
        _liveAlerts.tryEmit(alert)
    }

    private var backgroundWatch: Job? = null

    /** Worth keeping chat connected off screen for: signed in, with whisper or mention notifications on. */
    fun canNotifyLive(): Boolean {
        if (_account.value == null) return false
        val options = io.github.strawwaffle.glorp.notify.NotifyOptions(prefs, settings)
        return options.whispers || options.mentions
    }

    // The site's live socket: on screen only, not kept for background sound.
    private var liveJob: Job? = null

    private fun startLive() {
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            ready.await()
            LiveSocket(client, site, (session as? Session.Cookies)?.cookieHeader, reportedWatching)
                .run { update -> _live.value = _live.value.with(update) }
        }
    }

    /** Off screen: chat still reads what arrives (for notifications) but draws nothing. */
    @Volatile
    private var inBackground = false

    /**
     * The app left the screen. Chat stays connected while [stillWanted] says
     * so (a stream playing on with the screen off), so whispers and mentions
     * still notify at once; then it closes, as it always did.
     */
    fun stopWhenIdle(stillWanted: () -> Boolean) {
        inBackground = true
        watching?.cancel()
        watching = null
        liveJob?.cancel()
        liveJob = null
        backgroundWatch?.cancel()
        backgroundWatch = viewModelScope.launch {
            // Give the player a moment to decide whether it plays on.
            delay(2.seconds)
            while (stillWanted()) delay(15.seconds)
            stop()
        }
    }

    /** A message or whisper: dropped if its sender is ignored, else remembered for autocomplete and logged. */
    /** Who is in chat now, in lower case: from the roster on connect, then joins and quits. */
    private val present = HashSet<String>()

    private fun received(incoming: ChatLine): Boolean {
        // Names of people in chat now become tappable in it, as chat-gui marks them when a message comes.
        val line = if (incoming.kind != ChatLine.Kind.User || present.isEmpty()) incoming
        else nickWord.findAll(incoming.text).map { it.value }.filter { it.lowercase() in present }.distinct().toList()
            .let { if (it.isEmpty()) incoming else incoming.copy(mentioned = it) }
        val nick = line.nick
        if (nick != null) {
            if (nick in input.ignored) return false
            input.completer.heard(nick, line.at)
            if (line.kind == ChatLine.Kind.Whisper) {
                input.replyTo = nick
                _inbox.value = _inbox.value.received(nick, line.text, line.at, line.messageId)
                val id = line.messageId
                if (_inbox.value.open == nick.lowercase() && id != null) {
                    messages()?.let { api -> viewModelScope.launch { api.markRead(id) } }
                }
            }
        }
        return log.add(line, combo)
    }

    /**
     * Takes the poll down after [delayMs]: an ended one once it has been
     * seen, a running one that never heard its end (the connection dropped
     * as it closed) a little after its time is up.
     */
    private fun schedulePollEnd(delayMs: Long) {
        pollTimer?.cancel()
        pollTimer = viewModelScope.launch {
            delay(delayMs.coerceAtLeast(0).milliseconds)
            _poll.value = null
        }
    }

    private fun pollPayload(request: PollRequest) = buildJsonObject {
        put("weighted", JsonPrimitive(request.weighted))
        put("hidden", JsonPrimitive(request.hidden))
        put("time", JsonPrimitive(request.timeMs))
        put("question", JsonPrimitive(request.question))
        put("options", JsonArray(request.options.map({ JsonPrimitive(it) })))
    }

    override fun onCleared() {
        if (_account.value != null && _socketState.value == SocketState.Connected) {
            io.github.strawwaffle.glorp.notify.AlertCheck.heardUntilNow(prefs, socket?.lastHeardMs ?: 0)
        }
        youtube.close()
        client.close()
    }

    /**
     * Whether chat is scrolled up to read back (see ChatScreen): lines are
     * then kept rather than trimmed, and updates come less often.
     */
    @kotlin.concurrent.Volatile
    var readingBack = false

    private companion object {
        val publishInterval = 150.milliseconds
        val READING_INTERVAL = 1.seconds
        const val PINS_CLOSED = "pins-closed"
        const val POLL_GRACE_MS = 15_000L
        /** chat-gui's `chat.settings.synced`: the user this device's settings last synced for. */
        const val WARNINGS = "warnings"
        const val PREFERRED_PLATFORM = "preferred-platform"
        const val SETTINGS_SYNCED = "settings.synced"
        /** chat-gui's `chat.settings.pending`: the user with changes here the account's copy lacks. */
        const val SETTINGS_PENDING = "settings.pending"
    }

    /**
     * chat-gui's splat behind a finished combo of 10 or more: named, with a
     * hash, in the chat page's stylesheet, so read from there (once) rather
     * than kept in the app. Null if it can't be found.
     */
    private suspend fun comboSplat(site: Site): String? = try {
        val css = site.chatCss
        if (css == null) null
        else {
            val response = client.get(css) { header(io.ktor.http.HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
            if (!response.status.isSuccess()) null
            else Regex("""url\(["']?([^)"']*emote-splat[^)"']*)["']?\)""").find(response.bodyAsText())?.groupValues?.get(1)?.let { path ->
                if (path.startsWith("http")) path else css.substringBeforeLast('/') + "/" + path
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

}

/** A word that could be a nick, as chat-gui looks for them. */
private val nickWord = Regex("""\w{3,20}""")


/**
 * Set by the chat screen: given the lines about to be shown, it prepares and
 * measures rows for the newest of them on a background thread (see the
 * screen's RowPrefetch). Null when no chat is on screen.
 */
object LinePrefetch {
    @kotlin.concurrent.Volatile
    var hook: ((List<ChatLine>) -> Unit)? = null
}
