package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Where one copy of the site lives. destiny.gg and its test site
 * (omniliberal.dev) run the same code under different hosts, and the chat page
 * names its own socket and CDN, so the origin is the only setting.
 */
data class Site(
    val origin: String,
    val chatSocketUrl: String,
    val cdn: String,
) {
    companion object {
        /** A guess from the origin, used until (or if) the chat page can't be read. */
        fun guess(origin: String): Site {
            val host = origin.substringAfter("://").removePrefix("www.").trimEnd('/')
            return Site(origin.trimEnd('/'), "wss://chat.$host/ws", "https://cdn.$host")
        }

        /** Reads the `data-ws-url` and `data-cdn` attributes of the chat page. */
        fun fromChatPage(origin: String, html: String): Site? {
            val ws = attribute(html, "data-ws-url") ?: return null
            val cdn = attribute(html, "data-cdn") ?: return null
            return Site(origin.trimEnd('/'), ws, cdn.trimEnd('/'))
        }

        private fun attribute(html: String, name: String): String? =
            Regex("""$name="([^"]+)"""").find(html)?.groupValues?.get(1)

        suspend fun discover(client: HttpClient, origin: String): Site {
            val fallback = guess(origin)
            return try {
                val response = client.get("${fallback.origin}/embed/chat") {
                    header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
                }
                if (!response.status.isSuccess()) fallback
                else fromChatPage(origin, response.bodyAsText()) ?: fallback
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                fallback
            }
        }
    }
}
