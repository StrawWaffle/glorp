package io.github.strawwaffle.glorp.community

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.diag.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/** A reaction to a posted link: an emote (or `@` for replies naming the poster), how many, and who. */
data class LinkReaction(val name: String, val count: Int, val users: List<String>)

/** A link or picture posted in chat, as linkers.ooo logged it. */
data class LinkPost(
    val id: Long,
    val at: Long,
    val nick: String,
    val flairs: List<String>,
    val message: String,
    val url: String,
    /** image, video, youtube, reddit, imgur, twitter or audio. */
    val kind: String,
    val nsfw: Boolean,
    /** Most first. */
    val reactions: List<LinkReaction>,
)

/** A page of posts and the offset the next one starts from (the site's own, not always this one's plus its size). */
data class LinksPage(val posts: List<LinkPost>, val next: Int, val hasMore: Boolean)

/** A message in chat reacting to a post. */
data class ReactionMessage(val at: Long, val nick: String, val flairs: List<String>, val text: String)

/** What the feed shows: as linkers.ooo's own filters. */
data class LinksQuery(
    /** "newest" or "reactions". */
    val sort: String = "newest",
    /** For "reactions": 6h, 12h, 1d, 1w, 2w or 1m. */
    val timeframe: String = "1d",
    /** Kinds to show; empty for all. */
    val kinds: Set<String> = emptySet(),
    /** "exclude", "include" or "require". */
    val nsfw: String = "exclude",
    /** Only this person's posts. */
    val user: String? = null,
)

/**
 * linkers.ooo, Pizza's log of the links and pictures posted in destiny.gg
 * chat and the reactions to them. Read without a login, a page at a time
 * as the list is scrolled. It allows 60 requests a minute and says so in
 * `X-RateLimit-*` headers; once they're used up, nothing more is asked
 * until it says they've refilled. Pictures load from where they're hosted,
 * not through linkers.ooo's own proxy.
 */
class Linkers(private val client: HttpClient) {
    suspend fun page(query: LinksQuery, offset: Int): LinksPage? = ask("$BASE/api/media-links", {
        parameter("limit", PAGE)
        parameter("offset", offset)
        parameter("nsfw", query.nsfw)
        parameter("sort", query.sort)
        if (query.sort == "reactions") parameter("timeframe", query.timeframe)
        if (query.kinds.isNotEmpty()) parameter("types", query.kinds.sorted().joinToString(","))
        query.user?.takeIf { it.isNotBlank() }?.let { parameter("user", it.trim()) }
    }) { parseLinksPage(it, offset) }

    suspend fun reactions(post: LinkPost): List<ReactionMessage>? =
        ask("$BASE/api/media-links/${post.id}/reaction-messages", {}) { parseReactionMessages(it) }

    private suspend fun <T> ask(url: String, params: io.ktor.client.request.HttpRequestBuilder.() -> Unit, read: (String) -> T?): T? {
        if (resting) return null
        return try {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
                params()
            }
            heed(response)
            if (!response.status.isSuccess()) null else read(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.note("linkers", "request failed", e)
            null
        }
    }

    private fun heed(response: HttpResponse) {
        val remaining = response.headers["X-RateLimit-Remaining"]?.toIntOrNull()
        if (response.status != HttpStatusCode.TooManyRequests && remaining != 0) return
        waitUntil = restUntil(
            Clock.System.now().toEpochMilliseconds(),
            response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
            response.headers["X-RateLimit-Reset"]?.toLongOrNull(),
        )
    }

    /** Whether linkers.ooo asked to wait, and nothing goes to it now. */
    val resting: Boolean get() = Clock.System.now().toEpochMilliseconds() < waitUntil

    companion object {
        const val BASE = "https://linkers.ooo"
        const val PAGE = 20

        @kotlin.concurrent.Volatile
        private var waitUntil = 0L
    }
}

/** When to ask again: `Retry-After` seconds, or `X-RateLimit-Reset` as seconds from now or a time (epoch seconds); a minute if neither. */
internal fun restUntil(now: Long, retryAfter: Long?, reset: Long?): Long {
    val seconds = retryAfter
        ?: reset?.let { if (it > 1_000_000_000L) it - now / 1000 else it }
        ?: 60L
    return now + seconds.coerceIn(1, 3600) * 1000
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.names(): List<String> = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
private fun time(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

/** `{links: [{id, timestamp, user, message, user_flairs, url, mediaType, nsfw, reactions: {name: {count, users}}}], offset, hasMore}`. */
internal fun parseLinksPage(body: String, asked: Int): LinksPage? = runCatching {
    val o = chatJson.parseToJsonElement(body) as JsonObject
    val links = (o["links"] as? JsonArray ?: return null).mapNotNull { item ->
        val l = item as? JsonObject ?: return@mapNotNull null
        LinkPost(
            id = (l["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
            at = time(l.str("timestamp")) ?: return@mapNotNull null,
            nick = l.str("user") ?: return@mapNotNull null,
            flairs = l["user_flairs"].names(),
            message = l.str("message").orEmpty(),
            url = l.str("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null,
            kind = l.str("mediaType").orEmpty(),
            nsfw = (l["nsfw"] as? JsonPrimitive)?.booleanOrNull ?: false,
            reactions = (l["reactions"] as? JsonObject)?.mapNotNull { (name, r) ->
                val ro = r as? JsonObject ?: return@mapNotNull null
                LinkReaction(name, (ro["count"] as? JsonPrimitive)?.intOrNull ?: 0, ro["users"].names())
            }.orEmpty().filter { it.count > 0 }.sortedByDescending { it.count },
        )
    }
    LinksPage(
        links,
        next = (o["offset"] as? JsonPrimitive)?.intOrNull ?: (asked + Linkers.PAGE),
        hasMore = (o["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}.getOrNull()

/** `{messages: [{timestamp, user, message, user_flairs}]}`. */
internal fun parseReactionMessages(body: String): List<ReactionMessage>? = runCatching {
    val o = chatJson.parseToJsonElement(body) as JsonObject
    (o["messages"] as? JsonArray ?: return null).mapNotNull { item ->
        val m = item as? JsonObject ?: return@mapNotNull null
        ReactionMessage(
            at = time(m.str("timestamp")) ?: return@mapNotNull null,
            nick = m.str("user") ?: return@mapNotNull null,
            flairs = m["user_flairs"].names(),
            text = m.str("message").orEmpty(),
        )
    }
}.getOrNull()
