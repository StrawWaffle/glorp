package io.github.strawwaffle.glorp.video

import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.site.Embed
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/** A stream the app's own player can play. */
data class Playable(
    /** An HLS playlist with every quality; the player picks one. */
    val hlsUrl: String,
    val title: String?,
    val live: Boolean,
)

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
class Resolver(private val client: HttpClient) {
    suspend fun resolve(embed: Embed): Resolution = try {
        when (embed.platform) {
            "kick" -> kick(embed.id)
            else -> Resolution.Web
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
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
            else -> Resolution.Native(Playable(url, live.session_title, live = true))
        }
    }

    companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    }
}
