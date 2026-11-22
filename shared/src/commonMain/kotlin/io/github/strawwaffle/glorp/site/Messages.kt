package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.Conversation
import io.github.strawwaffle.glorp.chat.Whisper
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.chat.siteMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The site's whisper inbox, as chat-gui reads it. It takes the site's login
 * cookies; login keys are refused (403), so with a key Glorp has only the
 * whispers heard while it runs.
 */
class Messages(private val client: HttpClient, private val site: Site, private val cookies: Session.Cookies) {
    @Serializable
    private class Envelope<T>(val data: T? = null)

    @Serializable
    private class Page(val results: List<ConversationEntry> = emptyList())

    @Serializable
    private class ConversationEntry(
        val username: String = "",
        val unread: Int = 0,
        val lastMessage: String = "",
        val timestamp: JsonElement? = null,
        val lastMessageFromMe: Boolean = false,
    )

    @Serializable
    private class InboxEntry(
        val id: JsonElement? = null,
        val from: String = "",
        val message: String = "",
        val timestamp: JsonElement? = null,
    )

    @Serializable
    private class Count(val count: Int = 0)

    private fun HttpRequestBuilder.signed() {
        header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
        header(HttpHeaders.Cookie, cookies.cookieHeader)
    }

    /** The newest conversations (the first page, as chat-gui's list opens); null when refused or unreachable. */
    suspend fun conversations(): List<Conversation>? = fetch("/api/messages/conversations") { body ->
        chatJson.decodeFromString<Envelope<Page>>(body).data?.results.orEmpty()
            .filter { it.username.isNotEmpty() }
            .map {
                Conversation(
                    nick = it.username,
                    unread = it.unread,
                    lastAt = siteMillis(it.timestamp) ?: 0,
                    preview = it.lastMessage,
                    previewMine = it.lastMessageFromMe,
                )
            }
    }

    /** The history with [nick], oldest first. Reading it marks it read on the site, as in chat-gui. */
    suspend fun inbox(nick: String, me: String): List<Whisper>? =
        fetch("/api/messages/usr/${nick.encodeURLPathPart()}/inbox") { body ->
            chatJson.decodeFromString<List<InboxEntry>>(body).map {
                Whisper(
                    id = (it.id as? kotlinx.serialization.json.JsonPrimitive)?.content,
                    from = it.from,
                    text = it.message,
                    at = siteMillis(it.timestamp) ?: 0,
                    mine = it.from.equals(me, ignoreCase = true),
                )
            }.sortedBy { it.at }
        }

    suspend fun unreadCount(): Int? = fetch("/api/messages/unread-count") { body ->
        chatJson.decodeFromString<Envelope<Count>>(body).data?.count ?: 0
    }

    /** Marks one whisper read, for one that arrives while its conversation is open. */
    suspend fun markRead(id: String) {
        runCatching {
            client.post("${site.origin}/api/messages/msg/${id.encodeURLPathPart()}/open") {
                signed()
                header("X-CSRF-Guard", "YEE")
            }
        }
    }

    private suspend fun <T> fetch(path: String, read: (String) -> T): T? = try {
        val response = client.get("${site.origin}$path") { signed() }
        if (!response.status.isSuccess()) null else read(response.bodyAsText())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
