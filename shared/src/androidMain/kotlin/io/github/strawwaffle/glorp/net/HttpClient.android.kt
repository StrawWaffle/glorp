package io.github.strawwaffle.glorp.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            // OkHttp closes the socket when a ping goes unanswered, which is
            // how a connection that died without a close (a phone changing
            // networks) gets noticed. The server pings every 10 s as well.
            pingInterval(20, TimeUnit.SECONDS)
            connectTimeout(10, TimeUnit.SECONDS)
        }
    }
    install(WebSockets)
}
