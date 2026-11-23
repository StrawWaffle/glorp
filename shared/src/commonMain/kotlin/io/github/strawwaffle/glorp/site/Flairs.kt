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

/** A flair's badge picture, sizes in CSS pixels. */
data class Badge(val name: String, val label: String, val url: String, val width: Int, val height: Int)

/**
 * The site's flair list: name colours and badges.
 *
 * The website colours names with a stylesheet built from this list in reverse,
 * so the last matching rule there, the first matching entry here, wins. Taking
 * any other match miscolours people who hold several flairs.
 */
class Flairs private constructor(
    private val colored: List<Pair<String, Long>>,
    private val badges: Map<String, Pair<Int, Badge>>,
) {

    /** The name colour as 0xAARRGGBB, or null for the default colour. */
    /** The flair whose colour the name takes, as chat-gui puts it on the name. */
    fun nameFlair(features: List<String>): String? = colored.firstOrNull { it.first in features }?.first

    fun nameColor(features: List<String>): Long? {
        if (features.isEmpty()) return null
        return colored.firstOrNull { it.first in features }?.second
    }

    /** Every badge, in chat's order, for choosing which to hide. */
    val allBadges: List<Badge> get() = badges.values.sortedBy { it.first }.map { it.second }

    /** The badges chat shows beside a name, in its order: by priority, lowest first. */
    fun badges(features: List<String>): List<Badge> =
        features.mapNotNull { badges[it] }.sortedBy { it.first }.map { it.second }

    companion object {
        val None = Flairs(emptyList(), emptyMap())

        @Serializable
        private data class Image(val url: String? = null, val width: Int? = null, val height: Int? = null)

        @Serializable
        private data class Entry(
            val name: String = "",
            val label: String = "",
            val color: String? = null,
            val hidden: Boolean = false,
            val priority: Int = 0,
            val image: List<Image> = emptyList(),
        )

        fun parse(body: String): Flairs {
            val entries = chatJson.decodeFromString<List<Entry>>(body)
            val colored = entries.mapNotNull { entry ->
                val rgb = entry.color?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
                if (entry.name.isEmpty() || rgb == null) null else entry.name to (0xFF000000 or rgb)
            }
            val badges = entries.mapNotNull { entry ->
                val image = entry.image.firstOrNull() ?: return@mapNotNull null
                val url = image.url?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                if (entry.hidden || entry.name.isEmpty()) return@mapNotNull null
                entry.name to (entry.priority to Badge(entry.name, entry.label, url, image.width ?: 16, image.height ?: 16))
            }.toMap()
            return Flairs(colored, badges)
        }

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
