package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.chat.retryDelay
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The site's "live" socket, which every page of the site opens: it pushes
 * what's live, what's hosted, which embeds are banned and what chat is
 * watching, whenever that changes. Nothing is polled.
 */
class LiveSocket(private val client: HttpClient, site: Site) {
    private val url = "wss://live." + site.origin.substringAfter("://").removePrefix("www.").substringBefore('/')

    suspend fun run(onUpdate: (LiveUpdate) -> Unit) {
        var attempts = 0
        while (currentCoroutineContext().isActive) {
            attempts++
            var opened: TimeSource.Monotonic.ValueTimeMark? = null
            try {
                client.webSocket(urlString = url, request = { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }) {
                    opened = TimeSource.Monotonic.markNow()
                    for (frame in incoming) {
                        if (frame is Frame.Text) parseLiveMessage(frame.readText())?.let(onUpdate)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (opened?.let { it.elapsedNow() >= 5.seconds } == true) attempts = 0
            delay(retryDelay(attempts, Random::nextDouble))
        }
    }
}

/** One message from the live socket that bigscreen cares about. */
sealed interface LiveUpdate {
    data class Streams(val streams: List<LiveStream>) : LiveUpdate
    data class Hosting(val embed: Embed?) : LiveUpdate
    data class Banned(val keys: Set<String>) : LiveUpdate
    data class Embeds(val embeds: List<EmbedListing>) : LiveUpdate
}

fun LiveState.with(update: LiveUpdate): LiveState = when (update) {
    is LiveUpdate.Streams -> copy(streams = update.streams)
    is LiveUpdate.Hosting -> copy(hosting = update.embed)
    is LiveUpdate.Banned -> copy(banned = update.keys)
    is LiveUpdate.Embeds -> copy(embeds = update.embeds)
}

private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

fun parseLiveMessage(text: String): LiveUpdate? = try {
    val message = chatJson.parseToJsonElement(text).jsonObject
    val data = message["data"]
    when (message["type"].str()) {
        "dggApi:streamInfo" -> LiveUpdate.Streams(
            (data as? JsonObject)?.get("streams")?.let { it as? JsonObject }.orEmpty().mapNotNull { (platform, value) ->
                val stream = value as? JsonObject ?: return@mapNotNull null
                val id = stream["id"].str() ?: return@mapNotNull null
                LiveStream(
                    Embed(platform, id),
                    live = stream["live"]?.jsonPrimitive?.booleanOrNull == true,
                    title = stream["status_text"].str(),
                    preview = stream["preview"].str(),
                )
            },
        )
        "dggApi:hosting" -> LiveUpdate.Hosting(
            (data as? JsonObject)?.let { hosted ->
                val platform = hosted["platform"].str()
                val id = hosted["id"].str() ?: hosted["name"].str()
                if (platform != null && id != null) Embed(platform, id) else null
            },
        )
        "dggApi:bannedEmbeds" -> LiveUpdate.Banned(
            (data as? JsonArray).orEmpty().mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                val platform = o["platform"].str() ?: return@mapNotNull null
                val name = o["name"].str() ?: return@mapNotNull null
                "$platform/$name".lowercase()
            }.toSet(),
        )
        "dggApi:embeds" -> LiveUpdate.Embeds(
            (data as? JsonArray).orEmpty().mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                val platform = o["platform"].str() ?: return@mapNotNull null
                val id = o["id"].str() ?: return@mapNotNull null
                val meta = (o["mediaItem"] as? JsonObject)?.get("metadata") as? JsonObject
                EmbedListing(
                    Embed(platform, id),
                    watchers = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                    title = meta?.get("title").str(),
                    displayName = meta?.get("displayName").str(),
                    preview = meta?.get("previewUrl").str(),
                    live = meta?.get("live")?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull,
                )
            }.sortedByDescending { it.watchers },
        )
        else -> null
    }
} catch (_: IllegalArgumentException) {
    null
}

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
