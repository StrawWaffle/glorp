package io.github.strawwaffle.glorp.net

import io.ktor.client.HttpClient

/** The platform's HTTP client with websockets: OkHttp on Android, NSURLSession on iOS. */
expect fun createHttpClient(): HttpClient

/**
 * A client with a small disk cache, for the site's emote files: asked for
 * with `no-cache`, they're revalidated (304 Not Modified) rather than
 * downloaded in full on every launch. Only for requests that want that.
 */
expect fun createCachingHttpClient(): HttpClient

/**
 * A client for a source plugin's own requests: its own cookie jar (YouTube
 * hands out visitor cookies the plugin expects back) and timeouts so a stuck
 * request can't hold the plugin forever.
 */
expect fun createPluginHttpClient(): HttpClient
