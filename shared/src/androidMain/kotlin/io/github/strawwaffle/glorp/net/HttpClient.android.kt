package io.github.strawwaffle.glorp.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            // No websocket pings: destiny.gg's chat server never answers them,
            // so OkHttp would call every healthy connection dead. ChatSocket
            // checks the connection with the chat protocol's own PING instead.
            connectTimeout(10, TimeUnit.SECONDS)
        }
    }
    install(WebSockets)
}

private val cachingClient by lazy {
    HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                // The app's own cache folder (java.io.tmpdir on Android).
                cache(okhttp3.Cache(java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp", "glorp-http"), 8L * 1024 * 1024))
            }
        }
    }
}

actual fun createCachingHttpClient(): HttpClient = cachingClient

actual fun createPluginHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(io.ktor.client.plugins.cookies.HttpCookies)
    install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 30_000 }
}
