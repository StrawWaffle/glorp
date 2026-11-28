package io.github.strawwaffle.glorp.auth

import io.github.strawwaffle.glorp.diag.AppLog
import io.ktor.http.Url
import kotlin.time.Clock

/**
 * One sign-in attempt, told to the app log as `signin:` lines, so a problem
 * report shows which way someone signed in and where it stopped: the
 * provider, the pages it went through (host and path, never the query, where
 * codes and tokens are), load errors, whether the site's cookies were there
 * (yes or no, never their values), and how it ended.
 */
class SignInTrace(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val log: (String) -> Unit = { AppLog.note(TAG, it) },
) {
    private val started = now()
    private val seen = HashSet<String>()
    private var provider: String? = null
    private var ended = false

    /** The host last reached, for saying where an attempt was left. */
    var host: String? = null
        private set

    fun note(text: String) = log(text)

    /** A page started loading in the sign-in web view. */
    fun visited(url: String?) {
        val page = pageOf(url) ?: return
        host = page.substringBefore('/')
        if (provider == null) providerOf(host!!)?.let { provider = it; log("provider $it") }
        if (seen.add(page)) log("at $page")
    }

    /** The main page failed: an HTTP status, or the web view's own error code and description. */
    fun error(code: Int, url: String?, description: String? = null) {
        val where = pageOf(url)?.substringBefore('/') ?: "an unknown page"
        log("error $code at $where" + (description?.takeIf { it.isNotBlank() }?.let { " (${it.take(60)})" } ?: ""))
    }

    fun landed(url: String?) = log("back at ${pageOf(url) ?: "the site"}")

    fun cookies(sid: Boolean, rememberMe: Boolean) = log("cookies sid ${yes(sid)}, rememberme ${yes(rememberMe)}")

    /** How it ended: null for signed in, or what went wrong. */
    fun finished(error: String?) {
        if (ended) return
        ended = true
        log(if (error == null) "signed in after ${seconds()} s" else "not signed in after ${seconds()} s: ${error.take(120)}")
    }

    /** Closed before it landed. */
    fun cancelled() {
        if (ended) return
        ended = true
        log("cancelled at ${host ?: "the start"} after ${seconds()} s")
    }

    private fun seconds() = ((now() - started) / 1000).coerceAtLeast(0)

    companion object {
        const val TAG = "signin"

        private fun yes(b: Boolean) = if (b) "yes" else "no"

        /** `host/path` of an address, without its query or fragment; null for anything but http(s). */
        fun pageOf(url: String?): String? {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return null
            val parsed = runCatching { Url(url.substringBefore('#').substringBefore('?')) }.getOrNull() ?: return null
            val path = parsed.encodedPath.trimEnd('/').take(80)
            return parsed.host.lowercase() + path
        }

        /** The login provider a host belongs to, as the site names them; null for any other host. */
        fun providerOf(host: String): String? {
            val h = host.lowercase()
            fun under(domain: String) = h == domain || h.endsWith(".$domain")
            return when {
                under("twitch.tv") -> "twitch"
                h == "accounts.google.com" -> "google"
                under("kick.com") -> "kick"
                h == "api.twitter.com" || under("x.com") || under("twitter.com") -> "x"
                under("reddit.com") -> "reddit"
                under("discord.com") -> "discord"
                else -> null
            }
        }
    }
}
