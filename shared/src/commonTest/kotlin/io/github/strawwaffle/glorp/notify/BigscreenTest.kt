package io.github.strawwaffle.glorp.notify

import io.github.strawwaffle.glorp.chat.MemoryChatPrefs
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.EmbedListing
import io.github.strawwaffle.glorp.site.LiveState
import io.github.strawwaffle.glorp.site.LiveStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BigscreenTest {
    private val prefs = MemoryChatPrefs()
    private val options = BigscreenOptions(prefs)
    private var clock = 1_000_000L
    private val lookups = mutableListOf<String>()

    // YouTube videos belong to channels this test makes up; Kick and Twitch are their own channel.
    private val watch = BigscreenWatch(prefs, options, { embed, listing ->
        lookups += embed.key
        when (embed.platform) {
            "youtube" -> Channel("youtube/@${embed.id.substringBefore('-')}", "@" + embed.id.substringBefore('-'))
            "kick", "twitch" -> Channel("${embed.platform}/${embed.id.lowercase()}", listing?.displayName ?: embed.id)
            else -> null
        }
    }, now = { clock })

    private fun listing(key: String, watchers: Int, name: String? = null) =
        EmbedListing(Embed.parse(key)!!, watchers, "Title of $key", name ?: key.substringAfter('/'), null, true)

    private fun state(vararg embeds: EmbedListing, hosting: String? = null) =
        LiveState(embeds = embeds.toList(), hosting = hosting?.let { Embed.parse(it) }, known = true, listed = true)

    @Test
    fun theFirstLookOnlyTakesNote() = runTest {
        options.setHosts(true)
        options.follow(Channel("kick/anythingelse", "anythingelse"), Reach.Listed)
        assertEquals(emptyList(), watch.check(state(listing("kick/anythingelse", 300), hosting = "kick/emiok")))
    }

    @Test
    fun aNewHostNotifiesOnceAndCanBeMuted() = runTest {
        options.setHosts(true)
        watch.check(state())
        val alerts = watch.check(state(listing("kick/emiok", 50, "EmiOK"), hosting = "kick/emiok"))
        assertEquals(listOf("Destiny is hosting EmiOK"), alerts.map { it.title })
        assertEquals(AlertCheck.OPEN_EMBED + "kick/emiok", alerts.single().open)
        assertEquals(emptyList(), watch.check(state(listing("kick/emiok", 60, "EmiOK"), hosting = "kick/emiok")))
        // The button on the notification
        options.apply(alerts.single().actions.single().command)
        assertTrue(options.isMuted("kick/emiok"))
        watch.check(state())
        assertEquals(emptyList(), watch.check(state(hosting = "kick/emiok")))
    }

    @Test
    fun onlyFollowedHostsWhenAsked() = runTest {
        options.setHosts(true)
        options.setHostFilter(HostFilter.OnlyFollowed)
        watch.check(state())
        assertEquals(emptyList(), watch.check(state(hosting = "kick/xqc")))
        options.follow(Channel("twitch/someone", "someone"), Reach.First)
        assertEquals(listOf("Destiny is hosting someone"), watch.check(state(hosting = "twitch/someone")).map { it.title })
    }

    @Test
    fun aHostedChannelYouFollowNotifiesOnce() = runTest {
        options.setHosts(true)
        options.follow(Channel("kick/anythingelse", "anythingelse"), Reach.Listed)
        watch.check(state())
        val alerts = watch.check(state(listing("kick/anythingelse", 300), hosting = "kick/anythingelse"))
        assertEquals(listOf("Destiny is hosting anythingelse"), alerts.map { it.title })
    }

    @Test
    fun aFollowedChannelNotifiesWhenItComesIntoItsRange() = runTest {
        options.follow(Channel("kick/emiok", "EmiOK"), Reach.Top3)
        watch.check(state())
        // Fourth: not yet
        val below = arrayOf(listing("kick/a", 90), listing("kick/b", 80), listing("kick/c", 70), listing("kick/emiok", 60, "EmiOK"))
        assertEquals(emptyList(), watch.check(state(*below)))
        val alerts = watch.check(state(listing("kick/a", 90), listing("kick/emiok", 85, "EmiOK"), listing("kick/b", 80)))
        assertEquals(listOf("EmiOK is #2 on chat's embeds"), alerts.map { it.title })
        // Still there: quiet
        assertEquals(emptyList(), watch.check(state(listing("kick/emiok", 85, "EmiOK"))))
    }

    @Test
    fun inAndOutWithinHalfAnHourStaysQuiet() = runTest {
        options.follow(Channel("kick/emiok", "EmiOK"), Reach.Listed)
        watch.check(state())
        assertEquals(1, watch.check(state(listing("kick/emiok", 5))).size)
        clock += 10 * 60_000
        watch.check(state())
        assertEquals(emptyList(), watch.check(state(listing("kick/emiok", 5))))
        clock += 40 * 60_000
        watch.check(state())
        assertEquals(1, watch.check(state(listing("kick/emiok", 5))).size)
    }

    @Test
    fun aYouTubeVideoMatchesTheChannelYouFollow() = runTest {
        options.follow(Channel("youtube/@destiny", "@destiny"), Reach.Listed)
        watch.check(state())
        val alerts = watch.check(state(listing("youtube/destiny-abc123", 40, "Destiny")))
        assertEquals(listOf("@destiny is #1 on chat's embeds"), alerts.map { it.title })
        assertEquals(AlertCheck.OPEN_EMBED + "youtube/destiny-abc123", alerts.single().open)
    }

    @Test
    fun noYouTubeLookupsWhenNothingCouldMatch() = runTest {
        options.follow(Channel("kick/emiok", "EmiOK"), Reach.Listed)
        watch.check(state())
        watch.check(state(listing("youtube/someone-xyz", 40)))
        assertTrue(lookups.none { it.startsWith("youtube/") })
    }

    @Test
    fun pileOnsPassTheCountAndSkipMutedAndDestinysOwn() = runTest {
        options.setPileOns(true)
        options.setPileOnAt(50)
        options.mute(Channel("kick/muted", "muted"))
        watch.check(state())
        val own = LiveStream(Embed("kick", "destiny"), live = true, title = null, preview = null)
        val alerts = watch.check(
            state(listing("kick/destiny", 900), listing("kick/big", 120, "Big"), listing("kick/muted", 100), listing("kick/small", 20))
                .copy(streams = listOf(own)),
        )
        assertEquals(listOf("Chat piled onto Big"), alerts.map { it.title })
        assertEquals(listOf("Mute", "Follow"), alerts.single().actions.map { it.label })
        options.apply(alerts.single().actions[1].command)
        assertEquals(Reach.Listed, options.followOf("kick/big")?.reach)
    }

    @Test
    fun followingUnmutesAndMutingUnfollows() {
        options.mute(Channel("kick/x", "x"))
        options.follow(Channel("kick/x", "x"), Reach.First)
        assertTrue(!options.isMuted("kick/x"))
        options.mute(Channel("kick/x", "x"))
        assertNull(options.followOf("kick/x"))
    }

    @Test
    fun channelsFromWhatPeoplePaste() {
        assertEquals(Channel("kick/anythingelse", "anythingelse"), channelFromInput("https://kick.com/anythingelse"))
        assertEquals(Channel("kick/anythingelse", "anythingelse"), channelFromInput("#kick/anythingelse"))
        assertEquals(Channel("twitch/somebody", "Somebody"), channelFromInput("www.twitch.tv/Somebody/"))
        assertEquals(Channel("youtube/@destiny", "@destiny"), channelFromInput("https://www.youtube.com/@destiny/streams"))
        assertEquals(Channel("youtube/@destiny", "@destiny"), channelFromInput("@destiny"))
        assertEquals(
            Channel("youtube/channel/uc554ey5jnufdq3ydojyirow", "UC554eY5jNUfDq3yDOJYirOw"),
            channelFromInput("youtube.com/channel/UC554eY5jNUfDq3yDOJYirOw"),
        )
        assertNull(channelFromInput("https://www.youtube.com/watch?v=abc"))
        assertNull(channelFromInput("rumble.com/whatever"))
    }

    @Test
    fun oEmbedNamesTheChannel() {
        assertEquals(
            Channel("youtube/@destiny", "Destiny"),
            parseOEmbedChannel("""{"author_name":"Destiny","author_url":"https://www.youtube.com/@destiny"}"""),
        )
        assertEquals(
            Channel("youtube/channel/ucz9l5o-_-08eqh2cudho0yg", "Cristobal Tapia De Veer - Topic"),
            parseOEmbedChannel("""{"author_name":"Cristobal Tapia De Veer - Topic","author_url":"https://www.youtube.com/channel/UCz9l5o-_-08eQH2CUdHo0Yg"}"""),
        )
    }
}
