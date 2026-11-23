package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.diag.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class SocketState { Connecting, Connected, Waiting, Stopped }

/**
 * One reconnecting connection to the chat socket. It holds the connection
 * only while [run] is running; the caller runs it while the app is on screen
 * (or playing in picture-in-picture) and cancels it otherwise.
 *
 * No Origin header is sent. destiny.gg answers 403 to any origin but its own,
 * and a native client has none to send. Signed in, the login cookies go with
 * the handshake, as the browser sends them for chat-gui.
 */
class ChatSocket(
    private val client: HttpClient,
    private val url: String,
    private val cookieHeader: String?,
) {
    private val _state = MutableStateFlow(SocketState.Connecting)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private var current: DefaultClientWebSocketSession? = null
    private var halted = false

    suspend fun run(onFrame: suspend (String) -> Unit) {
        var attempts = 0
        while (currentCoroutineContext().isActive && !halted) {
            attempts++
            _state.value = SocketState.Connecting
            var opened: TimeSource.Monotonic.ValueTimeMark? = null
            try {
                client.webSocket(urlString = url, request = {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    if (cookieHeader != null) header(HttpHeaders.Cookie, cookieHeader)
                }) {
                    current = this
                    opened = TimeSource.Monotonic.markNow()
                    _state.value = SocketState.Connected
                    AppLog.note("chat", "connected")
                    var lastFrame = TimeSource.Monotonic.markNow()
                    // The server doesn't answer websocket pings, but it does
                    // answer a PING event. Only a connection quiet for half a
                    // minute is asked, and one that stays quiet is dropped:
                    // a phone that changed networks leaves a socket that
                    // looks open and never delivers again.
                    val watchdog = launch {
                        while (true) {
                            delay(heartbeatCheck)
                            val idle = lastFrame.elapsedNow()
                            if (idle >= deadAfter) {
                                close()
                                break
                            }
                            if (idle >= quietAfter) {
                                runCatching { send(Frame.Text("PING {\"timestamp\":${kotlin.time.Clock.System.now().toEpochMilliseconds()}}")) }
                            }
                        }
                    }
                    try {
                        for (frame in incoming) {
                            lastFrame = TimeSource.Monotonic.markNow()
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            // chat-gui answers the server's PING events the same way.
                            if (text.startsWith("PING ")) send(Frame.Text("PONG " + text.removePrefix("PING ")))
                            else onFrame(text)
                        }
                    } finally {
                        watchdog.cancel()
                        current = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.note("chat", "connection ended", e)
            }
            if (halted) break
            // The server completes the handshake before it checks the login,
            // so only a connection that stayed up resets the backoff, or a
            // rejected client would retry at the shortest delay forever.
            val stable = opened?.let { it.elapsedNow() >= stableAfter } ?: false
            if (stable) attempts = 0
            AppLog.note("chat", "reconnecting, attempt ${attempts + 1}")
            _state.value = SocketState.Waiting
            delay(retryDelay(attempts, Random::nextDouble))
        }
        _state.value = SocketState.Stopped
    }

    /** Stops for good, as chat-gui does after a ban or too many connections. */
    suspend fun halt() {
        halted = true
        current?.close()
    }

    /** Sends a chat message. False when not connected. */
    suspend fun sendMessage(text: String): Boolean =
        send("MSG", JsonObject(mapOf("data" to JsonPrimitive(text))))

    /** Sends a whisper. False when not connected. */
    suspend fun sendWhisper(nick: String, text: String): Boolean =
        send("PRIVMSG", JsonObject(mapOf("nick" to JsonPrimitive(nick), "data" to JsonPrimitive(text))))

    /** Sends any other event, such as a moderator's `MUTE` or `BAN`. False when not connected. */
    suspend fun sendEvent(event: String, payload: JsonObject): Boolean = send(event, payload)

    private suspend fun send(event: String, payload: JsonObject): Boolean {
        val session = current ?: return false
        return try {
            session.send(Frame.Text("$event $payload"))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val USER_AGENT = "Glorp (+https://github.com/StrawWaffle/glorp)"
        private val stableAfter = 5.seconds
        private val heartbeatCheck = 10.seconds
        private val quietAfter = 30.seconds
        private val deadAfter = 60.seconds
    }
}
