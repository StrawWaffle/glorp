package io.github.strawwaffle.glorp.video

import io.github.strawwaffle.glorp.diag.AppLog
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.site.Embed
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/** A stream the app's own player can play. */
data class Playable(
    /**
     * An HLS playlist with every quality (the player picks one), one MP4
     * file, or a DASH manifest (see [format]).
     */
    val hlsUrl: String,
    val title: String?,
    val live: Boolean,
    /** Where a recording starts, from a `?t=` in the link. */
    val startSeconds: Int? = null,
    val format: Format = Format.Hls,
    /** Sent with requests for the stream's media files (YouTube's recordings need them). */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Which video this is, whatever address it was given this time: a
     * recording looked up again (its address expired) picks up where it was.
     */
    val key: String? = null,
    /**
     * Live from Amazon IVS (Kick) or Twitch, whose playlists name the
     * segments being made: the player can sit [LOW_LATENCY_TARGET_MS] behind
     * real time rather than three segments behind the playlist's end.
     */
    val lowLatency: Boolean = false,
) {
    enum class Format { Hls, Mp4, Dash }
}

/** What looking up an embed found. */
sealed interface Resolution {
    data class Native(val playable: Playable) : Resolution
    /** The channel exists but isn't live. */
    data class Offline(val name: String) : Resolution
    /** No native way to play it (yet): use the platform's own player. */
    data object Web : Resolution
}

/**
 * Turns an embed into a stream the native player can play. Each platform's
 * lookup is the one its own site does; none of them is a published API, so a
 * failure falls back to the platform's web player rather than to nothing.
 */
class Resolver(private val client: HttpClient, private val youtube: YouTubePlugin? = null) {
    suspend fun resolve(embed: Embed): Resolution = try {
        when (embed.platform) {
            "kick" -> kick(embed.id)
            "kick-vod" -> kickVod(embed.id.substringAfter('/'), embed.startSeconds)
            "twitch" -> twitch(embed.id, vod = false)
            "twitch-vod" -> twitch(embed.id, vod = true, startSeconds = embed.startSeconds)
            "twitch-clip" -> twitchClip(embed.id)
            "youtube" -> youtube(embed.id, embed.startSeconds)
            "angelthump" -> angelThump(client, embed.id, io.github.strawwaffle.glorp.chat.ChatSocket.USER_AGENT)
            else -> Resolution.Web
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.note("player", "couldn't look up ${embed.key}, using the web player", e)
        Resolution.Web
    }

    @Serializable
    private data class KickChannel(
        val slug: String = "",
        val playback_url: String? = null,
        val livestream: KickLive? = null,
        val user: KickUser? = null,
    )

    @Serializable
    private data class KickLive(val session_title: String? = null, val is_live: Boolean = false)

    @Serializable
    private data class KickUser(val username: String? = null)

    /**
     * kick.com's own channel lookup. Its answer carries `playback_url`, the
     * channel's HLS playlist (Amazon IVS) with every quality up to 1080p60:
     * the embedded Kick player is held to 360p, this isn't.
     */
    private suspend fun kick(slug: String): Resolution {
        val response = client.get("https://kick.com/api/v2/channels/${slug.encodeURLPathPart()}") {
            // Kick answers 403 to anything that doesn't look like a browser.
            header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
            header(HttpHeaders.Accept, "application/json")
        }
        if (!response.status.isSuccess()) return Resolution.Web
        val channel = chatJson.decodeFromString<KickChannel>(response.bodyAsText())
        val live = channel.livestream
        val url = channel.playback_url
        return when {
            live == null || !live.is_live -> Resolution.Offline(channel.user?.username ?: slug)
            url.isNullOrEmpty() -> Resolution.Web
            else -> Resolution.Native(Playable(url, live.session_title, live = true, lowLatency = true))
        }
    }

    /**
     * YouTube through FUTO's Grayjay plugin. Live streams come back as HLS.
     * Recordings come back as separate files for each quality and for the
     * audio, which become one DASH manifest; their requests are rewritten as
     * the plugin's request modifier does (see YouTubeRanges on Android).
     */
    private suspend fun youtube(id: String, startSeconds: Int?): Resolution {
        val plugin = youtube ?: return Resolution.Web
        if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) return Resolution.Web
        val details = plugin.details(id) ?: return Resolution.Web
        val live = details.live?.url
        if (details.isLive) {
            return if (live != null) Resolution.Native(Playable(live, details.name, live = true)) else Resolution.Web
        }
        val manifest = ytDashManifest(details) ?: return Resolution.Web
        return Resolution.Native(
            Playable(
                dataUri("application/dash+xml", manifest),
                details.name,
                live = false,
                startSeconds = startSeconds,
                format = Playable.Format.Dash,
                headers = ytHeaders(details),
                key = "youtube/$id",
            ),
        )
    }

    @Serializable
    private data class KickVideo(val source: String? = null, val livestream: KickVideoStream? = null)

    @Serializable
    private data class KickVideoStream(val session_title: String? = null)

    /**
     * A Kick VOD (`channel/uuid` in the site's embed), from kick.com's own
     * video lookup, whose `source` is the recording's HLS playlist.
     */
    private suspend fun kickVod(uuid: String, startSeconds: Int?): Resolution {
        if (!Regex("[0-9a-fA-F-]{36}").matches(uuid)) return Resolution.Web
        val response = client.get("https://kick.com/api/v1/video/$uuid") {
            header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
            header(HttpHeaders.Accept, "application/json")
        }
        if (!response.status.isSuccess()) return Resolution.Web
        val video = chatJson.decodeFromString<KickVideo>(response.bodyAsText())
        val url = video.source?.takeIf { it.startsWith("https://") } ?: return Resolution.Web
        return Resolution.Native(Playable(url, video.livestream?.session_title, live = false, startSeconds = startSeconds))
    }

    @Serializable
    private data class TwitchToken(val value: String, val signature: String)

    /**
     * What twitch.tv's own player does: ask its web GraphQL API for a
     * playback token (with the public web client id), then ask usher, Twitch's
     * playlist server, for the HLS playlist that token opens. The playlist has
     * every quality, source included; audio-only is left out by the controls.
     * Ads Twitch puts in the stream play as they do on the site.
     */
    private suspend fun twitch(id: String, vod: Boolean, startSeconds: Int? = null): Resolution {
        val variables = if (vod) {
            JsonObject(mapOf("isLive" to JsonPrimitive(false), "login" to JsonPrimitive(""), "isVod" to JsonPrimitive(true), "vodID" to JsonPrimitive(id), "playerType" to JsonPrimitive("embed")))
        } else {
            JsonObject(mapOf("isLive" to JsonPrimitive(true), "login" to JsonPrimitive(id.lowercase()), "isVod" to JsonPrimitive(false), "vodID" to JsonPrimitive(""), "playerType" to JsonPrimitive("embed")))
        }
        val body = JsonObject(mapOf("operationName" to JsonPrimitive("PlaybackAccessToken_Template"), "query" to JsonPrimitive(TWITCH_TOKEN_QUERY), "variables" to variables))
        val response = client.post("https://gql.twitch.tv/gql") {
            header("Client-ID", TWITCH_WEB_CLIENT_ID)
            header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
            contentType(ContentType.Text.Plain)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) return Resolution.Web
        val data = chatJson.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonObject ?: return Resolution.Web
        val tokenJson = data[if (vod) "videoPlaybackAccessToken" else "streamPlaybackAccessToken"]
        if (tokenJson == null || tokenJson is JsonNull) return if (vod) Resolution.Web else Resolution.Offline(id)
        val token = chatJson.decodeFromJsonElement(TwitchToken.serializer(), tokenJson)
        val query = listOf(
            "sig" to token.signature, "token" to token.value, "allow_source" to "true", "allow_audio_only" to "true",
            "playlist_include_framerate" to "true", "player" to "twitchweb", "p" to Random.nextInt(0, 9_999_999).toString(),
            // Live: ask for the segments still being made (#EXT-X-TWITCH-PREFETCH), as Twitch's low-latency player does.
        ).let { if (vod) it else it + ("fast_bread" to "true") }.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
        val url = if (vod) "https://usher.ttvnw.net/vod/${id.encodeURLPathPart()}.m3u8?$query"
        else "https://usher.ttvnw.net/api/channel/hls/${id.lowercase().encodeURLPathPart()}.m3u8?$query"
        // Usher answers 404 for a channel that isn't live. Asking it costs one
        // small request and saves the player from failing on it.
        val check = client.get(url) { header(HttpHeaders.UserAgent, BROWSER_USER_AGENT) }
        return when {
            check.status.value == 404 && !vod -> Resolution.Offline(id)
            !check.status.isSuccess() -> Resolution.Web
            else -> Resolution.Native(Playable(url, null, live = !vod, startSeconds = startSeconds, lowLatency = !vod))
        }
    }

    @Serializable
    private data class ClipQuality(val quality: String = "", val frameRate: Double = 0.0, val sourceURL: String = "")

    /**
     * A clip, the way twitch.tv's clip player gets it: a clip playback token
     * and the clip's MP4 files, one per quality. Phones get 1080p if the
     * clip has it and the screen is big enough to matter, else the best below.
     */
    private suspend fun twitchClip(slug: String): Resolution {
        val body = JsonObject(
            mapOf(
                "operationName" to JsonPrimitive("VideoAccessToken_Clip"),
                "query" to JsonPrimitive(TWITCH_CLIP_QUERY),
                "variables" to JsonObject(mapOf("slug" to JsonPrimitive(slug))),
            ),
        )
        val response = client.post("https://gql.twitch.tv/gql") {
            header("Client-ID", TWITCH_WEB_CLIENT_ID)
            header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
            contentType(ContentType.Text.Plain)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) return Resolution.Web
        val clip = chatJson.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonObject?.get("clip")
        if (clip == null || clip is JsonNull) return Resolution.Web
        val o = clip.jsonObject
        val token = o["playbackAccessToken"]?.takeIf { it !is JsonNull }
            ?.let { chatJson.decodeFromJsonElement(TwitchToken.serializer(), it) } ?: return Resolution.Web
        val qualities = (o["videoQualities"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .map { chatJson.decodeFromJsonElement(ClipQuality.serializer(), it) }
            .filter { it.sourceURL.isNotEmpty() }
            .sortedByDescending { it.quality.toIntOrNull() ?: 0 }
        val pick = qualities.firstOrNull { (it.quality.toIntOrNull() ?: 0) <= MAX_CLIP_HEIGHT } ?: qualities.lastOrNull() ?: return Resolution.Web
        val url = pick.sourceURL + "?sig=" + token.signature.encodeURLParameter() + "&token=" + token.value.encodeURLParameter()
        val title = (o["title"] as? JsonPrimitive)?.contentOrNull
        return Resolution.Native(Playable(url, title, live = false, format = Playable.Format.Mp4))
    }

    companion object {
        private const val MAX_CLIP_HEIGHT = 1080
        private const val TWITCH_CLIP_QUERY =
            "query VideoAccessToken_Clip(\$slug: ID!) { clip(slug: \$slug) { id title " +
                "playbackAccessToken(params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"embed\"}) { signature value } " +
                "videoQualities { frameRate quality sourceURL } } }"

        // The id twitch.tv's own web player uses; it's public in every page.
        private const val TWITCH_WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        private const val TWITCH_TOKEN_QUERY =
            "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) " +
                "{ streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) { value signature __typename } " +
                "videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) { value signature __typename } }"

        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    }
}
