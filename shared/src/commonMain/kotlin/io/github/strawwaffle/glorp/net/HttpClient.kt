package io.github.strawwaffle.glorp.net

import io.ktor.client.HttpClient

/** The platform's HTTP client with websockets: OkHttp on Android, NSURLSession on iOS. */
expect fun createHttpClient(): HttpClient

/**
 * A client for a source plugin's own requests: its own cookie jar (YouTube
 * hands out visitor cookies the plugin expects back) and timeouts so a stuck
 * request can't hold the plugin forever.
 */
expect fun createPluginHttpClient(): HttpClient
