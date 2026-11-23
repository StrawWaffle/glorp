package io.github.strawwaffle.glorp.auth

/**
 * A login on the site, in one of the two forms chat accepts.
 *
 * Values are never printed: [toString] hides them, so a log line or a crash
 * report can't carry one.
 */
sealed interface Session {
    /** What goes in the `Cookie` header of the chat handshake. */
    val cookieHeader: String

    /**
     * A login key from the site's developer page (Connections → Create Login
     * Key). Chat takes it as the `authtoken` cookie. It lasts until removed on
     * that page, but the rest of the site doesn't accept it.
     *
     * "Sign in with destiny.gg" gives the same kind of token, made for Glorp's
     * app on the site: it runs out after an hour and is renewed with [refresh]
     * (see [io.github.strawwaffle.glorp.site.AppSignIn]).
     */
    class LoginKey(val key: String, val refresh: String? = null, val expiresAt: Long? = null) : Session {
        override val cookieHeader get() = "authtoken=$key"

        /** Made by "Sign in with destiny.gg", and so renewable. */
        val renewable: Boolean get() = refresh != null
        override fun toString() = "LoginKey(…${if (renewable) ", renewable" else ""})"
        override fun equals(other: Any?) = other is LoginKey && other.key == key && other.refresh == refresh && other.expiresAt == expiresAt
        override fun hashCode() = key.hashCode()
    }

    /**
     * The browser's own login cookies: `sid` is the session; `rememberme`
     * (there when "Remember Me" was ticked) lets the site issue a new `sid`
     * when the old one runs out.
     */
    class Cookies(val sid: String, val rememberMe: String?) : Session {
        override val cookieHeader
            get() = if (rememberMe.isNullOrEmpty()) "sid=$sid" else "sid=$sid; rememberme=$rememberMe"

        fun withSid(newSid: String) = Cookies(newSid, rememberMe)
        override fun toString() = "Cookies(sid=…, rememberme=${if (rememberMe == null) "none" else "…"})"
        override fun equals(other: Any?) = other is Cookies && other.sid == sid && other.rememberMe == rememberMe
        override fun hashCode() = sid.hashCode() * 31 + (rememberMe?.hashCode() ?: 0)
    }

    companion object {
        private val keyShape = Regex("""^[A-Za-z0-9]{32,128}$""")

        /** A login key as copied from the developer page, or null if it doesn't look like one. */
        fun loginKey(text: String): LoginKey? {
            val key = text.trim().removePrefix("authtoken=").trim()
            return if (keyShape.matches(key)) LoginKey(key) else null
        }

        /** Cookies pasted by hand, forgiving `sid=` prefixes and stray spaces. */
        fun cookies(sid: String, rememberMe: String): Cookies? {
            val s = sid.trim().removePrefix("sid=").trim()
            val r = rememberMe.trim().removePrefix("rememberme=").trim()
            if (s.isEmpty() || s.any { it.isWhitespace() || it == ';' }) return null
            if (r.any { it.isWhitespace() || it == ';' }) return null
            return Cookies(s, r.ifEmpty { null })
        }

        /** The stored form: a kind letter, then the values on their own lines. */
        fun encode(session: Session): String = when (session) {
            is LoginKey ->
                if (session.refresh == null) "k\n${session.key}"
                else "k\n${session.key}\n${session.refresh}\n${session.expiresAt ?: 0}"
            is Cookies -> "c\n${session.sid}\n${session.rememberMe.orEmpty()}"
        }

        fun decode(text: String): Session? {
            val parts = text.split('\n')
            return when (parts.firstOrNull()) {
                "k" -> parts.getOrNull(1)?.let(::loginKey)?.let { key ->
                    val refresh = parts.getOrNull(2)?.takeIf { keyShape.matches(it) }
                    if (refresh == null) key else LoginKey(key.key, refresh, parts.getOrNull(3)?.toLongOrNull())
                }
                "c" -> cookies(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
                else -> null
            }
        }
    }
}

/** Where the login is kept between launches. Android encrypts it with a Keystore key. */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session?)
}

/** Keeps the login only while the app runs. */
class MemorySessionStore : SessionStore {
    private var session: Session? = null
    override fun load() = session
    override fun save(session: Session?) {
        this.session = session
    }
}
