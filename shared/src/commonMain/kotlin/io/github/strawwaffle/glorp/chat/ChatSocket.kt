package io.github.strawwaffle.glorp.chat

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class SocketState { Connecting, Connected, Waiting }

/**
 * One reconnecting connection to the chat socket. It holds the connection
 * only while [run] is running; the caller runs it while the app is on screen
 * (or playing in picture-in-picture) and cancels it otherwise.
 *
 * No Origin header is sent. destiny.gg answers 403 to any origin but its own,
 * and a native client has none to send.
 */
class ChatSocket(
    private val client: HttpClient,
    private val url: String,
) {
    private val _state = MutableStateFlow(SocketState.Connecting)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    suspend fun run(onFrame: suspend (String) -> Unit) {
        var attempts = 0
        while (currentCoroutineContext().isActive) {
            attempts++
            _state.value = SocketState.Connecting
            var opened: TimeSource.Monotonic.ValueTimeMark? = null
            try {
                client.webSocket(urlString = url, request = { header(HttpHeaders.UserAgent, USER_AGENT) }) {
                    opened = TimeSource.Monotonic.markNow()
                    _state.value = SocketState.Connected
                    for (frame in incoming) {
                        if (frame is Frame.Text) onFrame(frame.readText())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Logged nowhere on purpose for now: the state flow shows it.
            }
            // The server completes the handshake before it checks the login,
            // so only a connection that stayed up resets the backoff, or a
            // rejected client would retry at the shortest delay forever.
            val stable = opened?.let { it.elapsedNow() >= stableAfter } ?: false
            if (stable) attempts = 0
            _state.value = SocketState.Waiting
            delay(retryDelay(attempts, Random::nextDouble))
        }
    }

    companion object {
        const val USER_AGENT = "Glorp (+https://github.com/StrawWaffle/glorp)"
        private val stableAfter = 5.seconds
    }
}
