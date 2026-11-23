package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.chat.BannedPhrase
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.ModWatch
import io.github.strawwaffle.glorp.chat.Mutelinks
import io.github.strawwaffle.glorp.chat.Nuke
import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Nukes, mutelinks and banned phrases from vyneer.me, the service behind the
 * d.gg utilities userscript. A small request for the lists' timestamps every
 * 30 seconds while chat is open; a list is fetched again only when its
 * timestamp moves.
 */
class VyneerTools(private val client: HttpClient) {
    private var stamps = emptyMap<String, Long>()

    /** Keeps [watch] up to date until cancelled. */
    suspend fun run(watch: ModWatch) {
        while (true) {
            val now = fetch("nmptimestamps")?.let(::readStamps)
            if (now != null) {
                if (now["nukes"] != stamps["nukes"]) fetch("nukes?ts=1")?.let(::readNukes)?.let { watch.nukes = it }
                if (now["mutelinks"] != stamps["mutelinks"]) fetch("mutelinks?ts=1")?.let(::readMutelinks)?.let { watch.mutelinks = it }
                if (now["phrases"] != stamps["phrases"]) fetch("phrases?ts=1")?.let(::readPhrases)?.let { watch.phrases = it }
                stamps = now
            }
            delay(30.seconds)
        }
    }

    private suspend fun fetch(path: String): JsonElement? = try {
        val response = client.get("https://vyneer.me/tools/$path") { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
        if (response.status.isSuccess()) chatJson.parseToJsonElement(response.bodyAsText()) else null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

private fun entries(body: JsonElement): List<JsonObject>? =
    ((body as? JsonObject)?.get("data") as? JsonArray)?.mapNotNull { it as? JsonObject }

internal fun readStamps(body: JsonElement): Map<String, Long>? =
    (body as? JsonObject)?.mapNotNull { (key, value) -> value.str()?.toLongOrNull()?.let { key to it } }?.toMap()

internal fun readNukes(body: JsonElement): List<Nuke>? = entries(body)?.mapNotNull { e ->
    val word = e["word"].str() ?: return@mapNotNull null
    Nuke(word, e["type"].str() ?: "mute", e["duration"].str() ?: "")
}

internal fun readPhrases(body: JsonElement): List<BannedPhrase>? = entries(body)?.mapNotNull { e ->
    val phrase = e["phrase"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    BannedPhrase(phrase, e["type"].str() ?: "mute", e["duration"].str() ?: "")
}

/** The newest entry says how mutelinks stands; none means off. */
internal fun readMutelinks(body: JsonElement): Mutelinks? = entries(body)?.let { list ->
    val e = list.firstOrNull() ?: return@let Mutelinks("off", null, "")
    Mutelinks(e["status"].str() ?: "off", e["user"].str(), e["duration"].str() ?: "")
}
