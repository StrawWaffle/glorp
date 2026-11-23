package io.github.strawwaffle.glorp.site

import io.ktor.http.encodeURLParameter

/**
 * Something bigscreen can show, named the way the site names it:
 * `#kick/destiny`, `#youtube/VIDEO_ID`, `#twitch-vod/123`.
 */
data class Embed(val platform: String, val id: String, val startSeconds: Int? = null) {
    val key: String get() = "$platform/$id"

    /** The platform's own player, as bigscreen embeds it (its `getEmbedUrl`). */
    fun playerUrl(site: Site): String? {
        val parent = site.origin.substringAfter("://").substringBefore('/').encodeURLParameter()
        val id = id.encodeURLParameter()
        return when (platform) {
            "twitch" -> "https://player.twitch.tv/?channel=$id&parent=$parent"
            "twitch-vod" -> "https://player.twitch.tv/?video=$id&parent=$parent"
            "twitch-clip" -> "https://clips.twitch.tv/embed?clip=$id&parent=$parent"
            "youtube" -> "https://www.youtube.com/embed/$id?autoplay=1&playsinline=1" + (startSeconds?.let { "&start=$it" } ?: "")
            "facebook" -> "https://www.facebook.com/plugins/video.php?href=" +
                "https://www.facebook.com/${this.id}".encodeURLParameter() + "&width=1920&height=1080"
            "rumble" -> "https://rumble.com/embed/$id"
            "vimeo" -> "https://player.vimeo.com/video/$id"
            "kick" -> "https://player.kick.com/$id?autoplay=true"
            "kick-vod" -> this.id.split('/').takeIf { it.size == 2 }?.let { (channel, video) ->
                "${site.origin}/api/kick/vod/${channel.encodeURLParameter()}/${video.encodeURLParameter()}"
            }
            "angelthump" -> "https://player.angelthump.com/?channel=$id"
            else -> null
        }
    }

    companion object {
        val platforms = setOf(
            "twitch", "twitch-vod", "twitch-clip", "youtube", "facebook", "rumble", "vimeo", "kick", "kick-vod", "angelthump",
        )

        /**
         * Reads an embed from a bigscreen link (`https://www.destiny.gg/bigscreen#kick/destiny`),
         * a hash as chat writes it (`#kick/destiny`), or a bare `kick/destiny`.
         */
        fun parse(text: String): Embed? {
            var rest = text.trim()
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                val before = rest.substring(0, hash)
                if (before.isNotEmpty() && !before.trimEnd('/').endsWith("/bigscreen")) return null
                rest = rest.substring(hash + 1)
            }
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val platform = rest.substring(0, slash).lowercase()
            if (platform !in platforms) return null
            var id = rest.substring(slash + 1)
            var start: Int? = null
            Regex("""[?&]t=(\d+)""").find(id)?.let {
                start = it.groupValues[1].toIntOrNull()
                id = id.substring(0, it.range.first)
            }
            id = id.trimEnd('/', '.', ',', '!', '?', ')')
            val allowed = if (platform == "kick-vod") Regex("""[\w-]+/[\w-]+""") else Regex("""[\w.@-]+""")
            if (!allowed.matches(id)) return null
            return Embed(platform, id, start)
        }
    }
}

/** Destiny's own stream on one platform, from the live socket's `streamInfo`. */
data class LiveStream(val embed: Embed, val live: Boolean, val title: String?, val preview: String?)

/** An embed chat is watching, from the live socket's `embeds`, most watched first. */
data class EmbedListing(
    val embed: Embed,
    val watchers: Int,
    val title: String?,
    val displayName: String?,
    val preview: String?,
    val live: Boolean?,
)

/** What bigscreen knows about what's on. */
data class LiveState(
    val streams: List<LiveStream> = emptyList(),
    val hosting: Embed? = null,
    val banned: Set<String> = emptySet(),
    val embeds: List<EmbedListing> = emptyList(),
    /** Whether the site has said yet which of Destiny's streams are live. */
    val known: Boolean = false,
) {
    companion object {
        /** Kick unless the settings say otherwise. */
        const val DEFAULT_PLATFORM = "kick"
    }

    val liveStreams: List<LiveStream> get() = streams.filter { it.live }

    /**
     * What bigscreen shows when nothing was picked: Destiny's stream on the
     * preferred platform if it's live there, else on the first live one, else
     * whatever is being hosted. (The site's `handleDataUpdate`.)
     */
    fun defaultEmbed(preferredPlatform: String = DEFAULT_PLATFORM): Embed? {
        val live = liveStreams
        return (live.firstOrNull { it.embed.platform == preferredPlatform } ?: live.firstOrNull())?.embed ?: hosting
    }

    fun isBanned(embed: Embed) = embed.key.lowercase() in banned
}
