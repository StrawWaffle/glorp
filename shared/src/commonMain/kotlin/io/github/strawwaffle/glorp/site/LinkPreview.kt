package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The previews chat-gui shows when a link is hovered (its linkpreview.js),
 * shown here when a link is tapped: YouTube's thumbnail, title and channel,
 * an X post's author, text and first picture (from api.fxtwitter.com), and
 * pictures from the hosts chat-gui trusts (or any host, with the site's
 * "preview images" setting). Rumble gets one too, from its oEmbed.
 *
 * Nothing is asked of anyone until the link is tapped.
 */
sealed interface LinkPreview {
    data class Video(val title: LinkTitles.Title, val embed: Embed?) : LinkPreview
    data class Post(val name: String, val handle: String, val text: String, val image: String?) : LinkPreview
    data class Picture(val url: String) : LinkPreview
}

/** What a preview would come from, worked out from the link alone. */
sealed interface PreviewKind {
    data object Video : PreviewKind
    data class Post(val id: String) : PreviewKind
    data class Picture(val url: String) : PreviewKind
}

/**
 * How the message holding a link is marked, as chat-gui reads it: a message
 * saying NSFL, NSFW or SPOILERS gets no picture or post preview until asked.
 */
enum class LinkFlag(val label: String) { Nsfl("NSFL"), Nsfw("NSFW"), Spoilers("spoilers") }

fun linkFlag(messageText: String): LinkFlag? = when {
    Regex("""\bNSFL\b""", RegexOption.IGNORE_CASE).containsMatchIn(messageText) -> LinkFlag.Nsfl
    Regex("""\bNSFW\b""", RegexOption.IGNORE_CASE).containsMatchIn(messageText) -> LinkFlag.Nsfw
    Regex("""\bSPOILERS\b""", RegexOption.IGNORE_CASE).containsMatchIn(messageText) -> LinkFlag.Spoilers
    else -> null
}

private val imageExtension = Regex("""\.(?:png|jpe?g|gif|webp|avif)$""", RegexOption.IGNORE_CASE)
private val imgurHosts = setOf("imgur.com", "www.imgur.com", "m.imgur.com")
private val imgurId = Regex("""^/(\w{5,8})(?:\.(?:gifv|mp4))?$""")

// Upload hosts that serve the file itself at a short link.
private val shortLinkHosts = setOf("catbox.moe", "files.catbox.moe", "kappa.lol", "gachi.gay", "femboy.beauty", "segs.lol")
private val shortLinkPath = Regex("""^/\w+$""")
private val knownImageHosts = imgurHosts + "i.imgur.com" + shortLinkHosts + "pbs.twimg.com"
private val xHosts = setOf("x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")
private val xPost = Regex("""^/\w{1,15}/status/(\d{2,19})""")

/** chat-gui's getImagePreviewUrl: only HTTPS, only known hosts unless [anyHost]. */
internal fun picturePreviewUrl(href: String, anyHost: Boolean): String? {
    val url = runCatching { Url(href) }.getOrNull() ?: return null
    if (url.protocol.name != "https") return null
    val host = url.host.lowercase()
    val path = url.encodedPath
    if (host in imgurHosts || host == "i.imgur.com") {
        imgurId.find(path)?.let { return "https://i.imgur.com/${it.groupValues[1]}m.jpg" }
    }
    if (host in shortLinkHosts && shortLinkPath.matches(path)) return href
    if (!anyHost && host !in knownImageHosts) return null
    return if (imageExtension.containsMatchIn(path)) href else null
}

internal fun xPostId(href: String): String? {
    val url = runCatching { Url(href) }.getOrNull() ?: return null
    if (url.host.lowercase() !in xHosts) return null
    return xPost.find(url.encodedPath)?.groupValues?.get(1)
}

/** Whether [href] gets a preview, and of what; null for a link that just opens. */
fun previewKind(href: String, anyHost: Boolean): PreviewKind? {
    if (mediaOf(href) != null) return PreviewKind.Video
    xPostId(href)?.let { return PreviewKind.Post(it) }
    picturePreviewUrl(href, anyHost)?.let { return PreviewKind.Picture(it) }
    return null
}

/** Loads the preview for [href] (see [previewKind]); null when there's nothing to show. */
suspend fun loadLinkPreview(client: HttpClient, titles: LinkTitles, href: String, kind: PreviewKind): LinkPreview? = when (kind) {
    PreviewKind.Video -> titles.load(href)?.let { LinkPreview.Video(it, youtubeEmbed(href)) }
    is PreviewKind.Post -> try {
        val response = client.get("https://api.fxtwitter.com/status/${kind.id}") { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
        if (response.status.isSuccess()) parseXPost(response.bodyAsText()) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    is PreviewKind.Picture -> LinkPreview.Picture(kind.url)
}

/** A YouTube video plays in Glorp's own player. */
private fun youtubeEmbed(href: String): Embed? =
    mediaOf(href)?.takeIf { it.platform == "youtube" }?.let { Embed("youtube", it.key.removePrefix("youtube:")) }

/** api.fxtwitter.com: `{tweet: {author: {name, screen_name}, text, media: {all: [{type, url, thumbnail_url}]}}}`. */
internal fun parseXPost(body: String): LinkPreview.Post? = runCatching {
    val tweet = chatJson.parseToJsonElement(body).jsonObject["tweet"] as? JsonObject ?: return null
    val author = tweet["author"] as? JsonObject ?: return null
    fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    val media = ((tweet["media"] as? JsonObject)?.get("all") as? JsonArray)?.firstOrNull() as? JsonObject
    val image = media?.let { if (it.str("type") == "photo") it.str("url") else it.str("thumbnail_url") }
    LinkPreview.Post(
        name = author.str("name") ?: return null,
        handle = author.str("screen_name") ?: return null,
        text = tweet.str("text").orEmpty(),
        image = image?.takeIf { it.startsWith("https://") },
    )
}.getOrNull()
