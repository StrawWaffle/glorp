package io.github.strawwaffle.glorp.video

import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A Rumble embed (`rumble/v7gm8po`), from the lookup Rumble's own embed
 * player makes: `embedJS/u3/?request=video`, whose answer names the video's
 * HLS playlists (`ua.hls.auto` has every quality) and, for older uploads,
 * MP4 files. One request per lookup.
 *
 * Cloudflare sits in front of rumble.com and challenges requests that say
 * they're a browser but don't behave like one; the app's own User-Agent
 * isn't challenged. The playlists and media come from Rumble's CDN without
 * cookies.
 */
internal suspend fun rumble(client: HttpClient, id: String, userAgent: String, startSeconds: Int?): Resolution {
    if (!Regex("v[a-z0-9]{3,12}").matches(id)) return Resolution.Web
    val response = client.get("https://rumble.com/embedJS/u3/?request=video&ver=2&v=$id") {
        header(HttpHeaders.UserAgent, userAgent)
        header(HttpHeaders.Accept, "application/json")
    }
    if (!response.status.isSuccess()) return Resolution.Web
    return rumbleStream(response.bodyAsText(), startSeconds)
}

/** Reads the embed player's answer; an unknown video comes back as `false`. */
internal fun rumbleStream(body: String, startSeconds: Int? = null): Resolution {
    val video = chatJson.parseToJsonElement(body) as? JsonObject ?: return Resolution.Web
    val title = video.string("title")?.let(::decodeRumbleEntities)
    val all = video.obj("ua")
    val hls = all?.obj("hls")?.obj("auto") ?: video.obj("u")?.obj("hls")
    val hlsUrl = hls?.string("url")
    // `live` is 2 while a stream is on (0 for a recording, 1 for one not started yet).
    val live = video.int("live") == 2 || hls?.obj("meta")?.bool("live") == true
    if (hlsUrl != null && hlsUrl.startsWith("https://")) {
        return Resolution.Native(
            Playable(
                if (live) withoutDvr(hlsUrl) else hlsUrl,
                title,
                live = live,
                startSeconds = if (live) null else startSeconds,
            ),
        )
    }
    if (live) return Resolution.Web
    // Older uploads: MP4 files by height, the best a phone needs.
    val mp4 = all?.obj("mp4")?.entries
        ?.mapNotNull { (height, file) ->
            val url = (file as? JsonObject)?.string("url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            (height.toIntOrNull() ?: return@mapNotNull null) to url
        }
        ?.sortedByDescending { it.first }
        ?.let { files -> files.firstOrNull { it.first <= MAX_MP4_HEIGHT } ?: files.lastOrNull() }
        ?.second
        ?: video.obj("u")?.obj("mp4")?.string("url")?.takeIf { it.startsWith("https://") }
        ?: return Resolution.Web
    return Resolution.Native(Playable(mp4, title, live = false, startSeconds = startSeconds, format = Playable.Format.Mp4))
}

/**
 * Live streams are offered with their whole past (`live-hls-dvr`): each
 * quality's playlist lists every two-second segment since the stream began,
 * a few hundred kilobytes after a few hours, fetched again every two
 * seconds. The same stream without the past (`live-hls`) is a couple of
 * kilobytes.
 */
internal fun withoutDvr(url: String) = url.replaceFirst("://rumble.com/live-hls-dvr/", "://rumble.com/live-hls/")

private const val MAX_MP4_HEIGHT = 1080

private fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun JsonObject.prim(key: String) = this[key] as? JsonPrimitive
private fun JsonObject.string(key: String) = prim(key)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(key: String) = prim(key)?.intOrNull
private fun JsonObject.bool(key: String) = prim(key)?.booleanOrNull

/** Titles come HTML-escaped (`They&apos;re`). */
private fun decodeRumbleEntities(s: String): String = s
    .replace("&quot;", "\"").replace("&apos;", "'").replace("&#039;", "'").replace("&#39;", "'")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
