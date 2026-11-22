package io.github.strawwaffle.glorp.video

import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * AngelThump's edge servers, and which to fall back on. The same `/hls/...`
 * stream is served by each of them, so a request can go to any. The choice
 * and the order of backups follow the community "AngelThump Edge Picker"
 * userscript (MIT): pick the edge that answers fastest, and when one fails,
 * try its nearest neighbours.
 */
object AngelThumpEdges {
    val all = listOf("nyc1", "sfo1", "ams1", "fra1", "sgp1").map { "$it.angelthump.com" }

    /** Backups for each edge, nearest first. */
    private val neighbours = mapOf(
        "nyc1" to listOf("sfo1", "ams1", "fra1", "sgp1"),
        "sfo1" to listOf("nyc1", "sgp1", "ams1", "fra1"),
        "ams1" to listOf("fra1", "nyc1", "sfo1", "sgp1"),
        "fra1" to listOf("ams1", "nyc1", "sfo1", "sgp1"),
        "sgp1" to listOf("sfo1", "fra1", "ams1", "nyc1"),
    ).mapKeys { "${it.key}.angelthump.com" }.mapValues { (_, near) -> near.map { "$it.angelthump.com" } }

    fun isEdge(host: String) = host in all

    /** The edges to try after [edge], nearest first. */
    fun backups(edge: String): List<String> = neighbours[edge].orEmpty()

    /** [url] with its host swapped for [edge]. */
    fun onEdge(url: String, edge: String): String = url.replaceFirst(Regex("""^(https?://)[^/]+"""), "$1$edge")

    fun hostOf(url: String): String? = Regex("""^https?://([^/:]+)""").find(url)?.groupValues?.get(1)
}

@Serializable
private class AtStream(val type: String? = null, val user: AtUser? = null)

@Serializable
private class AtUser(val username: String? = null, val display_name: String? = null, val title: String? = null)

@Serializable
private class AtToken(val token: String? = null)

private const val VIGOR = "https://vigor.angelthump.com"

/** The public id AngelThump's own web player sends when asking for a stream token. */
private const val PLAYER_ID = "SwnpX0RnA99YdRj0SPqs"

/**
 * AngelThump as its web player does it: the channel's stream from the API
 * (offline when there's none), a token for the playlist, then the media
 * playlist on whichever edge answers fastest.
 */
internal suspend fun angelThump(client: HttpClient, channel: String, userAgent: String): Resolution {
    if (!Regex("[A-Za-z0-9_-]{1,40}").matches(channel)) return Resolution.Web
    val listing = client.get("https://api.angelthump.com/v3/streams?username=${channel.encodeURLQueryComponent()}") {
        header(HttpHeaders.UserAgent, userAgent)
    }
    if (!listing.status.isSuccess()) return Resolution.Web
    val stream = chatJson.decodeFromString<List<AtStream>>(listing.bodyAsText()).firstOrNull { it.type == "live" }
        ?: return Resolution.Offline(channel)
    val tokenResponse = client.post("$VIGOR/${channel.encodeURLPathPart()}/token") {
        header(HttpHeaders.UserAgent, userAgent)
        header("Identifier", PLAYER_ID)
        contentType(ContentType.Application.Json)
        setBody("""{"patreon":false}""")
    }
    if (!tokenResponse.status.isSuccess()) return Resolution.Web
    val token = chatJson.decodeFromString<AtToken>(tokenResponse.bodyAsText()).token ?: return Resolution.Web
    val master = client.get("$VIGOR/hls/${channel.encodeURLPathPart()}.m3u8?token=${token.encodeURLQueryComponent()}") {
        header(HttpHeaders.UserAgent, userAgent)
    }
    if (!master.status.isSuccess()) return Resolution.Web
    val media = master.bodyAsText().lines().map { it.trim() }.firstOrNull { it.startsWith("https://") && it.endsWith(".m3u8") }
        ?: return Resolution.Web
    val edge = fastestEdge(client, media, userAgent) ?: AngelThumpEdges.hostOf(media)
    val url = if (edge != null) AngelThumpEdges.onEdge(media, edge) else media
    return Resolution.Native(Playable(url, stream.user?.title, live = true))
}

/**
 * Times the media playlist on every edge at once and keeps the fastest that
 * answers properly; null when none does within a few seconds. Five small
 * requests, once per stream opened.
 */
private suspend fun fastestEdge(client: HttpClient, media: String, userAgent: String): String? = coroutineScope {
    AngelThumpEdges.all.map { edge ->
        async {
            withTimeoutOrNull(4.seconds) {
                runCatching {
                    val started = TimeSource.Monotonic.markNow()
                    val response = client.get(AngelThumpEdges.onEdge(media, edge)) { header(HttpHeaders.UserAgent, userAgent) }
                    val body = response.bodyAsText()
                    if (response.status.isSuccess() && body.startsWith("#EXTM3U")) edge to started.elapsedNow() else null
                }.getOrNull()
            }
        }
    }.awaitAll().filterNotNull().minByOrNull { it.second }?.first
}
