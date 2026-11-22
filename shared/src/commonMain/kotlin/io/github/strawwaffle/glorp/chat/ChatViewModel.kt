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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    prefs: ChatPrefs = MemoryChatPrefs(),
) : ViewModel() {
    private val client = createHttpClient()
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
                input.completer.setCommands(commandWords(hasModPowers(features)))
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
        val command = parseCommand(text, hasModPowers(me.features))
        val needsChat = command is Command.Say || command is Command.Whisper || command is Command.Send || command is Command.PostEmbed
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
                    transmit {
                        chat!!.sendWhisper(command.nick, command.text).also {
                            if (it) notice("Whisper to ${command.nick}: ${command.text}")
                        }
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
            Command.Help -> notice(helpText(hasModPowers(me.features)))
            Command.EmoteList -> notice("Available emotes: ${_emotes.value.all.joinToString(", ") { it.prefix }}.")
            is Command.Watch -> {
                val embed = Embed.parse(command.link)
                if (embed == null) notice("Usage: /embed <link> OR /e <link> (bigscreen links and #platform/id)")
                else watch(embed)
            }
            is Command.Problem -> notice(command.text)
        }
        return true
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
            is ChatEvent.Error -> {
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
            if (line.kind == ChatLine.Kind.Whisper) input.replyTo = nick
        }
        return log.add(line, combo)
    }

    override fun onCleared() {
        youtube.close()
        client.close()
    }

    private companion object {
        val publishInterval = 150.milliseconds
    }
}
