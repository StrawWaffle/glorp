package io.github.strawwaffle.glorp.auth

/**
 * A login on the site: the two cookies chat-gui itself runs on. `sid` is the
 * session; `rememberme` (present when "remember me" was ticked) lets the site
 * issue a new `sid` when the old one runs out.
 */
class Session(val sid: String, val rememberMe: String?) {
    val cookieHeader: String
        get() = if (rememberMe.isNullOrEmpty()) "sid=$sid" else "sid=$sid; rememberme=$rememberMe"

    fun withSid(newSid: String) = Session(newSid, rememberMe)

    // Never print the values, not even in a log line or a crash report.
    override fun toString() = "Session(sid=…, rememberme=${if (rememberMe == null) "none" else "…"})"

    override fun equals(other: Any?) = other is Session && other.sid == sid && other.rememberMe == rememberMe
    override fun hashCode() = sid.hashCode() * 31 + (rememberMe?.hashCode() ?: 0)

    companion object {
        /** Reads `sid` and `rememberme` out of a `Cookie` header. */
        fun fromCookieHeader(header: String): Session? {
            val cookies = header.split(';').mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
            }.toMap()
            val sid = cookies["sid"]?.takeIf { it.isNotEmpty() } ?: return null
            return Session(sid, cookies["rememberme"]?.takeIf { it.isNotEmpty() })
        }

        /** Builds one from values pasted by hand, forgiving `sid=` prefixes and stray spaces. */
        fun fromPasted(sid: String, rememberMe: String): Session? {
            val s = sid.trim().removePrefix("sid=").trim()
            val r = rememberMe.trim().removePrefix("rememberme=").trim()
            if (s.isEmpty() || s.any { it.isWhitespace() || it == ';' }) return null
            if (r.any { it.isWhitespace() || it == ';' }) return null
            return Session(s, r.ifEmpty { null })
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
