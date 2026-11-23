package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.auth.Session

/**
 * The sign-in code Glorp's computer sign-in page (pages/signin on GitHub
 * Pages) shows as a QR code: `glorp-signin:1:<site host>:<sid>:<rememberme>`.
 * The host keeps a code for one site from signing in to another.
 */
fun readSignInCode(text: String, site: Site): Session.Cookies? {
    val parts = text.trim().split(':')
    if (parts.size != 5 || parts[0] != "glorp-signin" || parts[1] != "1") return null
    if (!parts[2].equals(site.host, ignoreCase = true)) return null
    return Session.cookies(parts[3], parts[4])
}
