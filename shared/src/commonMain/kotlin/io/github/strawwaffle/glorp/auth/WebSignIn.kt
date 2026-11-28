package io.github.strawwaffle.glorp.auth

import io.ktor.http.Url

/**
 * Signing in on the site's own login page, run in the app's sign-in web view
 * (`SiteLoginView`). Every login the site offers ends on a page of the site:
 * the provider sends the browser back to `/auth/<provider>`, which signs in
 * and redirects to the `follow` page, here `/profile`. Once there, the web
 * view's `sid` and `rememberme` cookies are the same login the computer
 * sign-in copies by hand.
 */
object WebSignIn {
    /** The page the web view opens: the login page, sending the browser to the profile once signed in. */
    fun startUrl(origin: String) = "${origin.trimEnd('/')}/login?follow=/profile"

    /**
     * Cookie values by name from a `Cookie`-header style string, as Android's
     * `CookieManager.getCookie` returns them (`a=1; b=2`). The first of a name
     * wins: that's the most specific path.
     */
    fun parseCookies(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val values = LinkedHashMap<String, String>()
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            if (name.isNotEmpty() && name !in values) values[name] = part.substring(eq + 1).trim()
        }
        return values
    }

    /** The login in those cookies, or null without a usable `sid`. */
    fun session(header: String?): Session.Cookies? {
        val cookies = parseCookies(header)
        val sid = cookies["sid"]?.takeIf { it.isNotEmpty() } ?: return null
        return Session.cookies(sid, cookies["rememberme"].orEmpty())
    }

    /**
     * Whether the web view reached a page only a signed-in visitor gets: the
     * profile, where the site sends a finished login. The site answers a
     * signed-out visit to `/profile` with the login page, so arriving here
     * means the login went through.
     */
    fun landedSignedIn(url: String?, origin: String): Boolean {
        val page = parse(url) ?: return false
        if (!onSite(page, origin)) return false
        val path = page.encodedPath.trimEnd('/')
        return path == "/profile" || path.startsWith("/profile/")
    }

    /** Whether [url] is the site's login page, where "Remember me" gets ticked. */
    fun isLoginPage(url: String?, origin: String): Boolean {
        val page = parse(url) ?: return false
        return onSite(page, origin) && page.encodedPath.trimEnd('/') == "/login"
    }

    /**
     * Ticks "Remember me" on the login page, so the site also sets
     * `rememberme` and the login outlives `sid`. The page has the form twice
     * (page and dialog); both get it.
     */
    const val TICK_REMEMBER_ME =
        "(function(){document.querySelectorAll('input[name=rememberme]').forEach(function(c){c.checked=true})})()"

    private fun parse(url: String?): Url? =
        url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { runCatching { Url(it) }.getOrNull() }

    /** Same site: the host with or without `www.`, over HTTPS. */
    private fun onSite(page: Url, origin: String): Boolean {
        val site = parse(origin) ?: return false
        return page.protocol.name == "https" && bare(page.host) == bare(site.host)
    }

    private fun bare(host: String) = host.lowercase().removePrefix("www.")
}
