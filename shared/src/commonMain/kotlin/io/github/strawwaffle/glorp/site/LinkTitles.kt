package io.github.strawwaffle.glorp.site

import androidx.compose.runtime.mutableStateMapOf
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Titles and channels for YouTube and Rumble links in chat, from their oEmbed
 * endpoints (no key, no login). Asked only for links on screen, each once,
 * two at a time; the last few hundred are remembered.
 */
class LinkTitles(private val client: HttpClient, private val scope: CoroutineScope) {
    data class Title(val title: String, val channel: String?, val platform: String, val thumbnail: String? = null)

    // Null: asked, and there's nothing to show (gone, private, offline).
    private val known = mutableStateMapOf<String, Title?>()
    private val order = ArrayDeque<String>()
    private val gate = Semaphore(2)

    /** The title for [url], once known; reading it redraws when it arrives. */
    fun titleOf(url: String): Title? = mediaOf(url)?.let { known[it.key] }

    /** [url]'s title, looked up now if it isn't known (for a preview, opened by a tap). */
    suspend fun load(url: String): Title? {
        val media = mediaOf(url) ?: return null
        if (media.key in known) return known[media.key]
        val title = gate.withPermit { fetch(media) }
        known[media.key] = title
        if (media.key !in order) {
            order.addLast(media.key)
            while (order.size > KEEP) known.remove(order.removeFirst())
        }
        return title
    }

    /** Looks up [url]'s title unless that's been done. */
    fun request(url: String) {
        val media = mediaOf(url) ?: return
        if (media.key in known || media.key in order) return
        order.addLast(media.key)
        while (order.size > KEEP) known.remove(order.removeFirst())
        scope.launch {
            val title = gate.withPermit { fetch(media) }
            if (media.key in order) known[media.key] = title
        }
    }

    private suspend fun fetch(media: Media): Title? = try {
        val response = client.get(media.oembed) {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            parameter("url", media.page)
            if (media.platform == "youtube") parameter("format", "json")
        }
        if (!response.status.isSuccess()) null else parseOembed(response.bodyAsText(), media.platform)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEEP = 300
    }
}

internal class Media(val key: String, val platform: String, val page: String, val oembed: String)

private val youtube = Regex("""^https?://(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?(?:[^#\s]*&)?v=|shorts/|live/|embed/)|youtu\.be/)([\w-]{11})""", RegexOption.IGNORE_CASE)
private val rumble = Regex("""^https?://(?:www\.)?rumble\.com/(v[a-z0-9]+-[^?#\s]*\.html)""", RegexOption.IGNORE_CASE)

/** A YouTube or Rumble video link, keyed so different spellings of one link share a title. */
internal fun mediaOf(url: String): Media? {
    youtube.find(url)?.let {
        val id = it.groupValues[1]
        return Media("youtube:$id", "youtube", "https://www.youtube.com/watch?v=$id", "https://www.youtube.com/oembed")
    }
    rumble.find(url)?.let {
        val path = it.groupValues[1]
        return Media("rumble:${path.lowercase()}", "rumble", "https://rumble.com/$path", "https://rumble.com/api/Media/oembed.json")
    }
    return null
}

internal fun parseOembed(body: String, platform: String): LinkTitles.Title? = runCatching {
    val o = chatJson.parseToJsonElement(body).jsonObject
    val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    LinkTitles.Title(
        title,
        (o["author_name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        platform,
        (o["thumbnail_url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("https://") },
    )
}.getOrNull()
