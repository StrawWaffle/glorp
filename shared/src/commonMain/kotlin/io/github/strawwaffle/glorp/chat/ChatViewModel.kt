package io.github.strawwaffle.glorp.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.auth.SessionStore
import io.github.strawwaffle.glorp.net.createHttpClient
import io.github.strawwaffle.glorp.site.Account
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.LiveSocket
import io.github.strawwaffle.glorp.site.LiveState
import io.github.strawwaffle.glorp.site.with
import io.github.strawwaffle.glorp.video.Resolution
import io.github.strawwaffle.glorp.video.Resolver
import io.github.strawwaffle.glorp.video.YouTubePlugin
import io.github.strawwaffle.glorp.net.createPluginHttpClient
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.Flairs
import io.github.strawwaffle.glorp.site.SessionCheck
import io.github.strawwaffle.glorp.site.Site
import io.github.strawwaffle.glorp.site.checkSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private var site = Site.guess(origin)
    private val log = ChatLog()
    private var session: Session? = store.load()
    private var socket: ChatSocket? = null

    /** The input's draft, history, suggestions and the ignore and highlight lists. */
    val input = ChatInputState(prefs)

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
    private var conversationsLoaded = false

    private fun messages(): io.github.strawwaffle.glorp.site.Messages? =
        (session as? Session.Cookies)?.let { io.github.strawwaffle.glorp.site.Messages(client, site, it) }

    /** Opens a conversation: its unread count clears and, with a cookie login, the site's history loads. */
    fun openConversation(nick: String) {
        _opening.value = null
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
    fun whisper(nick: String, text: String): Boolean {
        val chat = socket
        when {
            _account.value == null -> notice("Sign in to whisper.")
            chat == null || _socketState.value != SocketState.Connected -> notice("Not connected. Wait for the transmission to come back.")
            else -> {
                input.replyTo = nick
                unconfirmed.addLast(nick to text)
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
    private val events = Channel<Any>(capacity = 2_000, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var connection: Job? = null
    private var wanted = false
    private val ready = CompletableDeferred<Unit>()
    private val emotesLoaded = CompletableDeferred<Unit>()
    private val combo: (ChatLine) -> String? = { soleEmote(it, _emotes.value)?.prefix }

    init {
        viewModelScope.launch {
            site = Site.discover(client, origin)
            launch { _flairs.value = Flairs.fetch(client, site) }
            launch { _emotes.value = Emotes.fetch(client, site); emotesLoaded.complete(Unit) }
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
                input.completer.setEmotes(emotes.all.filter { mayUse(it, features, account?.subTier ?: 0) }.map { it.prefix })
                input.completer.setCommands(commandWords(account?.modPowers == true))
            }
        }
    }

    fun start() {
        wanted = true
        if (connection?.isActive == true) return
        connection = viewModelScope.launch {
            ready.await()
            // The backlog needs the emote list to group combos; don't wait long for it.
            withTimeoutOrNull(5.seconds) { emotesLoaded.await() }
            val chat = ChatSocket(client, site.chatSocketUrl, session?.cookieHeader)
            socket = chat
            launch { chat.state.collect { _socketState.value = it } }
            launch { LiveSocket(client, site).run { update -> _live.value = _live.value.with(update) } }
            chat.run { events.send(it) }
        }
    }

    fun stop() {
        wanted = false
        connection?.cancel()
        connection = null
        socket = null
        _socketState.value = SocketState.Waiting
    }

    private fun reconnect() {
        if (!wanted) return
        stop()
        start()
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
            is Command.Say -> transmit { chat!!.sendMessage(command.text) }
            is Command.Whisper -> {
                if (command.nick.equals(me.nick, ignoreCase = true)) {
                    notice("Cannot send a message to yourself")
                } else {
                    input.replyTo = command.nick
                    // Shown once the server takes it (PRIVMSGSENT): it can
                    // refuse, for an account too new or a name that's wrong.
                    unconfirmed.addLast(command.nick to command.text)
                    transmit {
                        chat!!.sendWhisper(command.nick, command.text).also { if (!it) unconfirmed.removeLastOrNull() }
                    }
                }
            }
            is Command.Send -> transmit { chat!!.sendEvent(command.event, command.payload) }
            is Command.PostEmbed -> {
                val first = command.args.firstOrNull()?.let(Embed::parse)
                val embed = first ?: _picked.value ?: _live.value.defaultEmbed()
                val words = if (first != null) command.args.drop(1) else command.args
                if (embed == null) notice("Nothing embedded")
                else transmit { chat!!.sendMessage(("#${embed.key} " + words.joinToString(" ")).trim()) }
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
            Command.Help -> notice(helpText(me.modPowers))
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
            for (frame in frames) {
                val pause = frame.removePrefix("sleep ").takeIf { it != frame }?.trim()?.toLongOrNull()
                if (pause != null) delay(pause.milliseconds)
                else events.send(frame.replace("${'$'}NOW", Clock.System.now().toEpochMilliseconds().toString()))
            }
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

    private fun transmit(action: suspend () -> Boolean) {
        viewModelScope.launch { if (!action()) notice("Couldn't send: the connection dropped.") }
    }

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
    }

    private fun notice(text: String) {
        events.trySend(ChatLine.info(text, Clock.System.now().toEpochMilliseconds()))
    }

    private suspend fun applyEvents() {
        val now = { Clock.System.now().toEpochMilliseconds() }
        while (true) {
            var changed = apply(events.receive(), now)
            while (true) {
                val next = events.tryReceive().getOrNull() ?: break
                changed = apply(next, now) or changed
            }
            if (changed) _lines.value = log.snapshot()
            delay(publishInterval)
        }
    }

    private suspend fun apply(item: Any, now: () -> Long): Boolean {
        if (item is ChatLine) return log.add(item)
        if (item is Forget) return item.nicks.fold(false) { changed, nick -> log.removeNick(nick) or changed }
        return when (val event = parseFrame(item as String, now)) {
            is ChatEvent.Message -> received(event.line)
            is ChatEvent.History -> event.lines.fold(false) { changed, line -> received(line) or changed }
            is ChatEvent.Names -> {
                // Only someone who can type needs names to complete.
                if (_account.value != null) input.completer.setRoster(rosterNicks(event.payload))
                false
            }
            is ChatEvent.Removal -> log.removeNick(event.nick)
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
                        _account.value = me
                        loadConversations()
                        false
                    }
                    session != null -> {
                        // Chat doesn't know the stored login any more: a key
                        // removed on the site, or cookies that ran out.
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
                if (event.code in whisperErrors) {
                    unconfirmed.removeFirstOrNull()?.let { (nick, _) -> _inbox.value = _inbox.value.refused(nick, errorText(event.code)) }
                }
                if (isFatal(event.code)) socket?.halt()
                log.add(ChatLine.info(errorText(event.code), now()))
            }
            null -> false
        }
    }

    /** A message or whisper: dropped if its sender is ignored, else remembered for autocomplete and logged. */
    private fun received(line: ChatLine): Boolean {
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
        youtube.close()
        client.close()
    }

    private companion object {
        val publishInterval = 150.milliseconds
        const val PINS_CLOSED = "pins-closed"
        /** The errors that answer a whisper, so it was never sent. */
        val whisperErrors = setOf("privmsgbanned", "privmsgaccounttooyoung", "notfound", "throttled", "duplicate", "muted", "needlogin", "nochatting")
        const val POLL_GRACE_MS = 15_000L
    }
}
