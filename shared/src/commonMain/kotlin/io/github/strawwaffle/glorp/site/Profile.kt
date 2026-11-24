package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Someone's membership card, as their profile page (`/u/<nick>`) shows it:
 * the front's photo, team, tier and fields, and the back's signature, fine
 * print and member number. Read from the page itself, which is the only place
 * the site gives the photo, team, nationality, standing and signature.
 */
data class ProfileCard(
    val photoUrl: String?,
    val tier: String?,
    val team: String?,
    val name: String,
    /** "Member since November 10, 2020", as the card words it. */
    val since: String?,
    /** Label and value, in the card's order: Nationality, Gender, Age, Standing. */
    val fields: List<Pair<String, String>>,
    /** `normal`, `decorated`, `flagged`…: the standing's colour. */
    val standing: String?,
    val bio: String?,
    val signature: Signature?,
    val finePrint: String?,
    val memberNo: String?,
    /** The barcode's bars as x and width, in a box [barcodeWidth] wide. */
    val bars: List<Pair<Int, Int>>,
    val barcodeWidth: Int,
)

/** A drawn signature: one SVG path in a view box, stroked. */
data class Signature(val width: Float, val height: Float, val path: String)

/** The profile page's card for [nick], or null if there isn't one (or the page couldn't be read). */
suspend fun lookUpProfile(client: HttpClient, site: Site, nick: String): ProfileCard? = try {
    val response = client.get("${site.origin}/u/$nick") { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
    if (!response.status.isSuccess()) null else parseProfileCard(response.bodyAsText(), site.origin)
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

internal fun parseProfileCard(html: String, origin: String): ProfileCard? {
    // `id-card`, or `id-card id-card--private` (where the site has already written "Private" for the hidden fields).
    val start = Regex("""class="id-card(?: [^"]*)?"""").find(html)?.range?.first ?: return null
    val card = html.substring(start)
    fun text(raw: String) = decodeEntities(raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()).takeIf { it.isNotEmpty() }
    fun byClass(cls: String, tag: String = "[a-z0-9]+"): String? =
        Regex("""<($tag)[^>]*class="[^"]*\b$cls\b[^"]*"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).find(card)?.groupValues?.get(2)?.let(::text)
    val name = byClass("id-card__name") ?: return null
    val photo = Regex("""<img[^>]*class="[^"]*\bid-card__photo\b[^"]*"[^>]*src="([^"]+)"""").find(card)?.groupValues?.get(1)
        ?: Regex("""<img[^>]*src="([^"]+)"[^>]*class="[^"]*\bid-card__photo\b""").find(card)?.groupValues?.get(1)
    val team = Regex("""id-card__team[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(card)?.groupValues?.get(1)?.let(::text)
    val tier = Regex("""id-card__tier[^"]*"[^>]*>(.*?)</span>\s*</span>""", RegexOption.DOT_MATCHES_ALL).find(card)?.groupValues?.get(1)?.let(::text)
    val fields = Regex(
        """<div class="id-card__field">\s*<span class="id-card__label">(.*?)</span>\s*<span class="([^"]*)">(.*?)</span>""",
        RegexOption.DOT_MATCHES_ALL,
    ).findAll(card).mapNotNull { m -> text(m.groupValues[1])?.let { label -> text(m.groupValues[3])?.let { label to it } } }.toList()
    val standing = Regex("""id-card__standing--([a-z]+)""").find(card)?.groupValues?.get(1)
    val sigSvg = Regex("""<svg[^>]*class="[^"]*\bid-card__signature-image\b[^"]*"[^>]*viewBox="0 0 ([\d.]+) ([\d.]+)"[^>]*>(.*?)</svg>""", RegexOption.DOT_MATCHES_ALL).find(card)
    val signature = sigSvg?.let { m ->
        val d = Regex("""<path[^>]*\sd="([^"]+)"""").findAll(m.groupValues[3]).joinToString(" ") { it.groupValues[1] }
        if (d.isBlank()) null else Signature(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), d)
    }
    val barSvg = Regex("""<svg[^>]*class="[^"]*\bid-card__barcode-bars\b[^"]*"[^>]*viewBox="0 0 (\d+) [\d.]+"[^>]*>(.*?)</svg>""", RegexOption.DOT_MATCHES_ALL).find(card)
    val bars = barSvg?.let { m ->
        Regex("""<rect x="(\d+)" width="(\d+)"""").findAll(m.groupValues[2]).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
    }.orEmpty()
    return ProfileCard(
        photoUrl = photo?.let { if (it.startsWith("http")) it else origin.trimEnd('/') + "/" + it.trimStart('/') },
        tier = tier,
        team = team,
        name = name,
        since = byClass("id-card__since"),
        fields = fields,
        standing = standing,
        bio = byClass("id-card__bio-text"),
        signature = signature,
        finePrint = byClass("id-card__fine-print", "p"),
        memberNo = byClass("id-card__member-no-value"),
        bars = bars,
        barcodeWidth = barSvg?.groupValues?.get(1)?.toInt() ?: 0,
    )
}

private fun decodeEntities(s: String): String = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&#039;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
