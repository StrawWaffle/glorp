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
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Chat for the screens: survives rotation, holds the socket only while
 * [start] has been called without a matching [stop].
 */
class ChatViewModel(origin: String, private val store: SessionStore) : ViewModel() {
    private val client = createHttpClient()
    private var site = Site.guess(origin)
    private val log = ChatLog()
    private var session: Session? = store.load()
    private var socket: ChatSocket? = null

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

    init {
        viewModelScope.launch {
            site = Site.discover(client, origin)
            launch { _flairs.value = Flairs.fetch(client, site) }
            launch { _emotes.value = Emotes.fetch(client, site) }
            // Like chat-gui on page load: ask who the cookies belong to first,
            // which also renews `sid` from `rememberme` when it has run out.
            // A login key can only be checked by chat itself, on connect.
            (session as? Session.Cookies)?.let { refresh(it) }
            ready.complete(Unit)
        }
        viewModelScope.launch { applyEvents() }
    }

    fun start() {
        wanted = true
        if (connection?.isActive == true) return
        connection = viewModelScope.launch {
            ready.await()
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
     * Sends what was typed. Handles the commands chat-gui handles for
     * everyone (`/me`, `/w`); others aren't supported yet and say so rather
     * than going to chat as text. Returns whether the input can be cleared.
     */
    fun send(input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty()) return false
        val chat = socket
        if (_account.value == null) {
            notice("Sign in to chat.")
            return false
        }
        if (chat == null || _socketState.value != SocketState.Connected) {
            notice("Not connected. Wait for the transmission to come back.")
            return false
        }
        val whisper = Regex("""^/(?:w|whisper|msg|tell|t|notify)\s+(\S+)\s+(.+)$""", RegexOption.DOT_MATCHES_ALL).find(text)
        viewModelScope.launch {
            val sent = when {
                whisper != null -> chat.sendWhisper(whisper.groupValues[1], whisper.groupValues[2]).also {
                    if (it) notice("Whisper to ${whisper.groupValues[1]}: ${whisper.groupValues[2]}")
                }
                text.startsWith("/") && !text.startsWith("/me ") -> {
                    notice("${text.substringBefore(' ')} isn't supported in Glorp yet.")
                    true
                }
                else -> chat.sendMessage(text)
            }
            if (!sent) notice("Couldn't send: the connection dropped.")
        }
        return true
    }

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
        return when (val event = parseFrame(item as String, now)) {
            is ChatEvent.Message -> log.add(event.line)
            is ChatEvent.History -> event.lines.fold(false) { changed, line -> log.add(line) or changed }
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

    override fun onCleared() {
        client.close()
    }

    private companion object {
        val publishInterval = 150.milliseconds
    }
}
