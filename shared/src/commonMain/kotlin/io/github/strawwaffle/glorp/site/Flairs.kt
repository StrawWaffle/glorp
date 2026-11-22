package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * The site's flair list, read for name colours.
 *
 * The website colours names with a stylesheet built from this list in reverse,
 * so the last matching rule there, the first matching entry here, wins. Taking
 * any other match miscolours people who hold several flairs.
 */
class Flairs private constructor(private val colored: List<Pair<String, Long>>) {

    /** The name colour as 0xAARRGGBB, or null for the default colour. */
    fun nameColor(features: List<String>): Long? {
        if (features.isEmpty()) return null
        return colored.firstOrNull { it.first in features }?.second
    }

    companion object {
        val None = Flairs(emptyList())

        @Serializable
        private data class Entry(val name: String = "", val color: String? = null)

        fun parse(body: String): Flairs = Flairs(
            chatJson.decodeFromString<List<Entry>>(body).mapNotNull { entry ->
                val rgb = entry.color?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
                if (entry.name.isEmpty() || rgb == null) null else entry.name to (0xFF000000 or rgb)
            },
        )

        suspend fun fetch(client: HttpClient, site: Site): Flairs = try {
            val response = client.get("${site.cdn}/flairs/flairs.json") {
                header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            }
            if (response.status.isSuccess()) parse(response.bodyAsText()) else None
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            None
        }
    }
}
