package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.settingsFromSite
import io.github.strawwaffle.glorp.chat.settingsToSite
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement

/**
 * The chat settings saved with the account, read and written as chat-gui
 * does. Cookies only: login keys are refused.
 */
class SiteSettings(private val client: HttpClient, private val site: Site, private val cookies: Session.Cookies) {
    /** The account's copy; null when it couldn't be read (which must never look like an empty copy). */
    suspend fun load(): Map<String, JsonElement>? = try {
        val response = client.get("${site.origin}/api/chat/me/settings") {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            header(HttpHeaders.Cookie, cookies.cookieHeader)
        }
        if (response.status.isSuccess()) settingsFromSite(response.bodyAsText()) else null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Replaces the account's copy with [settings]; whether the site took it. */
    suspend fun save(settings: Map<String, JsonElement>): Boolean = try {
        client.post("${site.origin}/api/chat/me/settings") {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            header(HttpHeaders.Cookie, cookies.cookieHeader)
            header("X-CSRF-Guard", "YEE")
            contentType(ContentType.Text.Plain)
            setBody(settingsToSite(settings))
        }.status.isSuccess()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
