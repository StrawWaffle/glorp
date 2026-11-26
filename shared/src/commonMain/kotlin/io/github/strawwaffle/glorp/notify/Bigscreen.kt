package io.github.strawwaffle.glorp.notify

import io.github.strawwaffle.glorp.chat.ChatPrefs
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.EmbedListing
import io.github.strawwaffle.glorp.site.LiveState
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/*
 * Bigscreen notifications: what Destiny hosts, channels you follow showing up
 * on chat's embeds list, and chat piling onto something. All of it comes from
 * the site's live socket (what's hosted and what chat is watching); Glorp
 * never asks a platform whether a channel is live. The one extra request is
 * YouTube's oEmbed, once per video, to learn which channel a video is from.
 */

/** How far up chat's embeds list a channel you follow has to be to notify. */
enum class Reach(val label: String, val menu: String) {
    Listed("On the list", "When it's on the list"),
    Top3("Top 3", "When it's in the top 3"),
    First("#1", "When it's #1"),
    ;

    fun covers(rank: Int) = when (this) {
        Listed -> true
        Top3 -> rank <= 3
        First -> rank == 1
    }
}

/**
 * A channel, keyed `platform/name` in lower case: `kick/xqc`, `twitch/name`,
 * `youtube/@handle` or `youtube/channel/uc…`. Something that isn't a channel
 * (a Rumble video, say) is keyed by its embed, so it can still be muted.
 */
data class Channel(val key: String, val name: String)

data class Followed(val key: String, val name: String, val reach: Reach)

enum class HostFilter { AllButMuted, OnlyFollowed }

/** The bigscreen notification settings, kept on this phone. */
class BigscreenOptions(private val prefs: ChatPrefs) {
    val hosts: Boolean get() = prefs.get(HOSTS) == "true"
    fun setHosts(on: Boolean) = prefs.put(HOSTS, on.toString())

    val hostFilter: HostFilter get() = if (prefs.get(HOST_FILTER) == "followed") HostFilter.OnlyFollowed else HostFilter.AllButMuted
    fun setHostFilter(filter: HostFilter) = prefs.put(HOST_FILTER, if (filter == HostFilter.OnlyFollowed) "followed" else "all")

    val pileOns: Boolean get() = prefs.get(PILE_ONS) == "true"
    fun setPileOns(on: Boolean) = prefs.put(PILE_ONS, on.toString())

    /** How many in chat have to be watching something for a pile-on. */
    val pileOnAt: Int get() = prefs.get(PILE_ON_AT)?.toIntOrNull()?.takeIf { it in PILE_ON_STEPS } ?: 50
    fun setPileOnAt(count: Int) = prefs.put(PILE_ON_AT, count.toString())

    val followed: List<Followed>
        get() = readList(FOLLOWED).mapNotNull { o ->
            val key = o.str("k") ?: return@mapNotNull null
            val reach = Reach.entries.firstOrNull { it.name == o.str("r") } ?: Reach.Listed
            Followed(key, o.str("n") ?: key, reach)
        }

    val muted: List<Channel>
        get() = readList(MUTED).mapNotNull { o -> o.str("k")?.let { Channel(it, o.str("n") ?: it) } }

    val any: Boolean get() = hosts || pileOns || followed.isNotEmpty()

    fun followOf(key: String): Followed? = followed.firstOrNull { it.key == key.lowercase() }
    fun isMuted(key: String): Boolean = muted.any { it.key == key.lowercase() }

    /** Follows [channel] (or changes when it notifies); following unmutes it. */
    fun follow(channel: Channel, reach: Reach) {
        val key = channel.key.lowercase()
        writeFollowed(followed.filter { it.key != key } + Followed(key, channel.name, reach))
        writeMuted(muted.filter { it.key != key })
    }

    fun unfollow(key: String) = writeFollowed(followed.filter { it.key != key.lowercase() })

    /** Never notifies for [channel] again; muting stops following it. */
    fun mute(channel: Channel) {
        val key = channel.key.lowercase()
        writeMuted(muted.filter { it.key != key } + Channel(key, channel.name))
        writeFollowed(followed.filter { it.key != key })
    }

    fun unmute(key: String) = writeMuted(muted.filter { it.key != key.lowercase() })

    /** Carries out a notification's button (an [AlertAction.command]). */
    fun apply(command: String) {
        val parts = command.split('\t')
        val key = parts.getOrNull(1) ?: return
        val name = parts.getOrNull(2) ?: key
        when (parts[0]) {
            "mute" -> mute(Channel(key, name))
            "unfollow" -> unfollow(key)
            "follow" -> follow(Channel(key, name), Reach.Listed)
        }
    }

    private fun readList(key: String): List<JsonObject> =
        prefs.get(key)?.let { runCatching { chatJson.parseToJsonElement(it).jsonArray.mapNotNull { e -> e as? JsonObject } }.getOrNull() }.orEmpty()

    private fun writeFollowed(list: List<Followed>) = prefs.put(
        FOLLOWED,
        JsonArray(list.map { JsonObject(mapOf("k" to JsonPrimitive(it.key), "n" to JsonPrimitive(it.name), "r" to JsonPrimitive(it.reach.name))) }).toString(),
    )

    private fun writeMuted(list: List<Channel>) = prefs.put(
        MUTED,
        JsonArray(list.map { JsonObject(mapOf("k" to JsonPrimitive(it.key), "n" to JsonPrimitive(it.name))) }).toString(),
    )

    companion object {
        val PILE_ON_STEPS = listOf(25, 50, 100, 200)
        private const val HOSTS = "bigscreen.hosts"
        private const val HOST_FILTER = "bigscreen.hostFilter"
        private const val PILE_ONS = "bigscreen.pileOns"
        private const val PILE_ON_AT = "bigscreen.pileOnAt"
        private const val FOLLOWED = "bigscreen.followed"
        private const val MUTED = "bigscreen.muted"

        fun muteCommand(channel: Channel) = "mute\t${channel.key}\t${channel.name}"
        fun unfollowCommand(key: String) = "unfollow\t$key"
        fun followCommand(channel: Channel) = "follow\t${channel.key}\t${channel.name}"
    }
}

/** Platforms whose embeds are channels (or, for YouTube, whose videos can be traced to one). */
val followablePlatforms = setOf("kick", "twitch", "youtube")

/**
 * A channel typed or pasted to follow: `kick.com/name`, `twitch.tv/name`,
 * `youtube.com/@handle`, `youtube.com/channel/UC…`, `@handle` (YouTube),
 * or `#kick/name` as chat writes embeds. Null for anything else.
 */
fun channelFromInput(text: String): Channel? {
    val s = text.trim().removePrefix("#")
    if (s.isEmpty()) return null
    Regex("""^@([\w.-]{3,30})$""").find(s)?.let { return Channel("youtube/@${it.groupValues[1].lowercase()}", "@${it.groupValues[1]}") }
    Regex("""^(kick|twitch)/([\w-]{2,40})$""", RegexOption.IGNORE_CASE).find(s)?.let {
        return Channel("${it.groupValues[1].lowercase()}/${it.groupValues[2].lowercase()}", it.groupValues[2])
    }
    val url = s.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("m.")
    val host = url.substringBefore('/').lowercase()
    val path = url.substringAfter('/', "").substringBefore('?').substringBefore('#').trimEnd('/')
    val first = path.substringBefore('/')
    return when (host) {
        "kick.com" -> first.takeIf { Regex("""[\w-]{2,40}""").matches(it) }?.let { Channel("kick/${it.lowercase()}", it) }
        "twitch.tv" -> first.takeIf { Regex("""\w{2,40}""").matches(it) && it.lowercase() !in setOf("videos", "directory") }
            ?.let { Channel("twitch/${it.lowercase()}", it) }
        "youtube.com" -> when {
            first.startsWith("@") && first.length > 1 -> Channel("youtube/${first.lowercase()}", first)
            first == "channel" -> path.split('/').getOrNull(1)?.takeIf { Regex("""UC[\w-]{22}""").matches(it) }
                ?.let { Channel("youtube/channel/${it.lowercase()}", it) }
            else -> null
        }
        else -> null
    }
}

/**
 * Which channel an embed is from. Kick and Twitch embeds are channels;
 * a YouTube embed is a video, looked up once with YouTube's oEmbed (well
 * under a kilobyte) and remembered. Other platforms have no channel here.
 */
class ChannelLookup(private val client: HttpClient, private val prefs: ChatPrefs) {
    /** What's already known, without asking anyone: for the embeds list. */
    fun cached(embed: Embed, listing: EmbedListing? = null): Channel? = when (embed.platform.lowercase()) {
        "kick", "twitch" -> Channel("${embed.platform.lowercase()}/${embed.id.lowercase()}", listing?.displayName ?: embed.id)
        "youtube" -> youtube()[embed.id]
        else -> null
    }

    suspend fun channelOf(embed: Embed, listing: EmbedListing? = null): Channel? {
        cached(embed, listing)?.let { return it }
        if (embed.platform.lowercase() != "youtube") return null
        val body = try {
            val response = client.get("https://www.youtube.com/oembed") {
                header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
                parameter("format", "json")
                parameter("url", "https://www.youtube.com/watch?v=${embed.id}")
            }
            if (response.status.isSuccess()) response.bodyAsText() else return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val channel = parseOEmbedChannel(body) ?: return null
        val known = youtube().toMutableMap()
        known.remove(embed.id)
        known[embed.id] = channel
        // The newest few hundred videos are plenty: chat's list changes slowly.
        val kept = known.entries.toList().takeLast(KEEP)
        prefs.put(
            YOUTUBE,
            JsonObject(kept.associate { (id, c) -> id to JsonArray(listOf(JsonPrimitive(c.key), JsonPrimitive(c.name))) }).toString(),
        )
        return channel
    }

    private fun youtube(): Map<String, Channel> = prefs.get(YOUTUBE)?.let { text ->
        runCatching {
            chatJson.parseToJsonElement(text).jsonObject.mapNotNull { (id, value) ->
                val pair = value as? JsonArray ?: return@mapNotNull null
                val key = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                id to Channel(key, (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: key)
            }.toMap()
        }.getOrNull()
    }.orEmpty()

    companion object {
        private const val YOUTUBE = "bigscreen.youtube"
        private const val KEEP = 300
    }
}

/** The channel in an oEmbed answer: `author_url` is `…/@handle` or `…/channel/UC…`. */
internal fun parseOEmbedChannel(body: String): Channel? = runCatching {
    val o = chatJson.parseToJsonElement(body).jsonObject
    val url = (o["author_url"] as? JsonPrimitive)?.contentOrNull ?: return null
    val name = (o["author_name"] as? JsonPrimitive)?.contentOrNull
    val path = url.substringAfter("youtube.com/", "").trimEnd('/')
    val key = when {
        path.startsWith("@") -> "youtube/${path.lowercase()}"
        path.startsWith("channel/") -> "youtube/${path.lowercase()}"
        else -> return null
    }
    Channel(key, name ?: path)
}.getOrNull()

/**
 * Decides what's new in what bigscreen knows: a new host, a followed channel
 * coming into its range on chat's list, a pile-on. What was already told is
 * kept in [prefs], shared by the app and the background check so neither
 * repeats the other. The very first look only takes note.
 *
 * Something that leaves its range and comes back within half an hour stays
 * quiet, so a channel moving in and out of the top 3 notifies once.
 */
class BigscreenWatch(
    private val prefs: ChatPrefs,
    private val options: BigscreenOptions,
    private val lookup: suspend (Embed, EmbedListing?) -> Channel?,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun check(state: LiveState): List<Alert> {
        val memory = Memory.read(prefs.get(SEEN))
        val first = memory == null
        val seen = memory?.seen?.toMutableMap() ?: mutableMapOf()
        val wasInside = memory?.inside.orEmpty()
        val at = now()
        val alerts = mutableListOf<Alert>()
        val told = mutableSetOf<String>()

        // Destiny's own streams show up on the list too; they're "Destiny goes live".
        val own = state.streams.map { it.embed.key.lowercase() }.toSet()
        val embeds = state.embeds.filter { !state.isBanned(it.embed) && it.embed.key.lowercase() !in own }
            .sortedByDescending { it.watchers }
        val anyYouTube = (options.followed.map { it.key } + options.muted.map { it.key }).any { it.startsWith("youtube/") }

        // A YouTube video's channel costs a request: only when it could matter.
        suspend fun channelOf(embed: Embed, listing: EmbedListing?, needed: Boolean): Channel? =
            if (embed.platform.lowercase() == "youtube" && !needed) null else lookup(embed, listing)

        // The host
        val hosted = state.hosting
        val hostKey = hosted?.key?.lowercase()
        if (hostKey != memory?.host && hosted != null && !first && options.hosts) {
            val listing = embeds.firstOrNull { it.embed.key.equals(hosted.key, ignoreCase = true) }
            val channel = channelOf(hosted, listing, needed = true)
            val key = channel?.key ?: hostKey!!
            val followed = options.followOf(key)
            val wanted = !options.isMuted(key) && !options.isMuted(hostKey!!) &&
                (options.hostFilter == HostFilter.AllButMuted || followed != null)
            if (wanted) {
                val name = channel?.name ?: listing?.displayName ?: hosted.id
                val about = listOfNotNull(listing?.title, platformName(hosted.platform)).joinToString(" · ")
                alerts += Alert(
                    Alert.Kind.Bigscreen, "Destiny is hosting $name", about, AlertCheck.OPEN_EMBED + hosted.key,
                    listOf(AlertAction("Mute $name", BigscreenOptions.muteCommand(Channel(key, name)))),
                )
                told += key
                seen[key] = at
            }
        }

        // Channels you follow, and pile-ons
        val inside = mutableSetOf<String>()
        embeds.forEachIndexed { index, listing ->
            val rank = index + 1
            val embedKey = listing.embed.key.lowercase()
            val pileOn = options.pileOns && listing.watchers >= options.pileOnAt
            val channel = if (options.followed.isNotEmpty() || options.muted.isNotEmpty() || pileOn) channelOf(listing.embed, listing, needed = anyYouTube || pileOn) else null
            val key = channel?.key ?: embedKey
            if (options.isMuted(key) || options.isMuted(embedKey)) return@forEachIndexed
            val followed = options.followOf(key)
            val inRange = (followed != null && followed.reach.covers(rank)) || pileOn
            if (!inRange) return@forEachIndexed
            inside += key
            if (first || key in told || key in wasInside) return@forEachIndexed
            val last = seen[key]
            if (last != null && at - last < QUIET_MS) return@forEachIndexed
            val name = channel?.name ?: listing.displayName ?: listing.embed.id
            val watching = "${listing.watchers} in chat watching"
            alerts += if (followed != null) {
                Alert(
                    Alert.Kind.Bigscreen,
                    "$name is #$rank on chat's embeds",
                    listOfNotNull(listing.title, watching).joinToString(" · "),
                    AlertCheck.OPEN_EMBED + listing.embed.key,
                    listOf(AlertAction("Stop following", BigscreenOptions.unfollowCommand(key))),
                )
            } else {
                Alert(
                    Alert.Kind.Bigscreen,
                    "Chat piled onto $name",
                    listOfNotNull(watching, platformName(listing.embed.platform), listing.title).joinToString(" · "),
                    AlertCheck.OPEN_EMBED + listing.embed.key,
                    listOfNotNull(
                        AlertAction("Mute", BigscreenOptions.muteCommand(Channel(key, name))),
                        if (channel != null) AlertAction("Follow", BigscreenOptions.followCommand(channel)) else null,
                    ),
                )
            }
            seen[key] = at
        }

        // Forget what was told over a day ago.
        val kept = seen.filterValues { at - it < DAY_MS }
        prefs.put(SEEN, Memory(hostKey, inside + told, kept).write())
        return alerts
    }

    private class Memory(val host: String?, val inside: Set<String>, val seen: Map<String, Long>) {
        fun write() = JsonObject(
            mapOf(
                "host" to JsonPrimitive(host),
                "inside" to JsonArray(inside.map(::JsonPrimitive)),
                "seen" to JsonObject(seen.mapValues { JsonPrimitive(it.value) }),
            ),
        ).toString()

        companion object {
            fun read(text: String?): Memory? = text?.let {
                runCatching {
                    val o = chatJson.parseToJsonElement(it).jsonObject
                    Memory(
                        (o["host"] as? JsonPrimitive)?.contentOrNull,
                        (o["inside"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonPrimitive)?.contentOrNull }.toSet(),
                        (o["seen"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap(),
                    )
                }.getOrNull()
            }
        }
    }

    companion object {
        private const val SEEN = "bigscreen.seen"
        private const val QUIET_MS = 30 * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
    }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
