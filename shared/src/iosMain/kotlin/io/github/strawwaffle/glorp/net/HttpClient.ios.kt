package io.github.strawwaffle.glorp.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    // No websocket pings: the chat server never answers them (see ChatSocket).
    install(WebSockets)
}

actual fun createPluginHttpClient(): HttpClient = HttpClient(Darwin) {
    install(io.ktor.client.plugins.cookies.HttpCookies)
    install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 30_000 }
}
