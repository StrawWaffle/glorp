package io.github.strawwaffle.glorp.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.strawwaffle.glorp.net.createHttpClient
import io.github.strawwaffle.glorp.site.Flairs
import io.github.strawwaffle.glorp.site.Site
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

/**
 * Chat for the screens: survives rotation, holds the socket only while
 * [start] has been called without a matching [stop].
 */
class ChatViewModel(origin: String) : ViewModel() {
    private val client = createHttpClient()
    private var site = Site.guess(origin)
    private val log = ChatLog()

    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    private val _flairs = MutableStateFlow(Flairs.None)
    val flairs: StateFlow<Flairs> = _flairs.asStateFlow()

    private val _socketState = MutableStateFlow(SocketState.Connecting)
    val socketState: StateFlow<SocketState> = _socketState.asStateFlow()

    // A busy chat sends several frames a second. They queue here and are
    // applied in batches, so the list is redrawn a few times a second at most
    // instead of once per message.
    private val frames = Channel<String>(capacity = 2_000, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var connection: Job? = null
    private val ready: Job = viewModelScope.launch {
        site = Site.discover(client, origin)
        _flairs.value = Flairs.fetch(client, site)
    }

    init {
        viewModelScope.launch { applyFrames() }
    }

    fun start() {
        if (connection?.isActive == true) return
        connection = viewModelScope.launch {
            ready.join()
            val socket = ChatSocket(client, site.chatSocketUrl)
            launch { socket.state.collect { _socketState.value = it } }
            socket.run { frames.send(it) }
        }
    }

    fun stop() {
        connection?.cancel()
        connection = null
        _socketState.value = SocketState.Waiting
    }

    private suspend fun applyFrames() {
        val now = { Clock.System.now().toEpochMilliseconds() }
        while (true) {
            var changed = apply(frames.receive(), now)
            while (true) {
                val next = frames.tryReceive().getOrNull() ?: break
                changed = apply(next, now) or changed
            }
            if (changed) _lines.value = log.snapshot()
            delay(publishInterval)
        }
    }

    private fun apply(raw: String, now: () -> Long): Boolean = when (val event = parseFrame(raw, now)) {
        is ChatEvent.Message -> log.add(event.line)
        is ChatEvent.History -> event.lines.fold(false) { changed, line -> log.add(line) or changed }
        is ChatEvent.Removal -> log.removeNick(event.nick)
        null -> false
    }

    override fun onCleared() {
        client.close()
    }

    private companion object {
        val publishInterval = 150.milliseconds
    }
}
