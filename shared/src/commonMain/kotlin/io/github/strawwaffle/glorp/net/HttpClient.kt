package io.github.strawwaffle.glorp.net

import io.ktor.client.HttpClient

/** The platform's HTTP client with websockets: OkHttp on Android, NSURLSession on iOS. */
expect fun createHttpClient(): HttpClient
