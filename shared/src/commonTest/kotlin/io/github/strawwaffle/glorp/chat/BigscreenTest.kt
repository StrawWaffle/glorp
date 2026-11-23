package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.LiveState
import io.github.strawwaffle.glorp.site.LiveUpdate
import io.github.strawwaffle.glorp.site.Site
import io.github.strawwaffle.glorp.site.parseLiveMessage
import io.github.strawwaffle.glorp.site.with
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BigscreenTest {
    private val site = Site("https://www.destiny.gg", "wss://chat.destiny.gg/ws", "https://cdn")

    @Test
    fun readsEmbedsTheWaysChatWritesThem() {
        assertEquals(Embed("kick", "destiny"), Embed.parse("#kick/destiny"))
        assertEquals(Embed("youtube", "abc_DEF-123"), Embed.parse("https://www.destiny.gg/bigscreen#youtube/abc_DEF-123"))
        assertEquals(Embed("youtube", "abc", 90), Embed.parse("#youtube/abc?t=90"))
        assertEquals(Embed("kick-vod", "destiny/1a2b-3c"), Embed.parse("#kick-vod/destiny/1a2b-3c"))
        assertNull(Embed.parse("#notaplatform/x"))
        assertNull(Embed.parse("https://example.com/page#kick/destiny"))
        assertNull(Embed.parse("#kick/"))
    }

    @Test
    fun buildsPlayerAddressesLikeBigscreen() {
        assertEquals("https://player.kick.com/destiny?autoplay=true", Embed("kick", "destiny").playerUrl(site))
        assertEquals("https://player.twitch.tv/?channel=xqc&parent=www.destiny.gg", Embed("twitch", "xqc").playerUrl(site))
        assertEquals("https://www.youtube.com/embed/abc?autoplay=1&playsinline=1&start=5", Embed("youtube", "abc", 5).playerUrl(site))
    }

    @Test
    fun chatLinksToEmbedsOpenInThePlayer() {
        val parts = segments(ChatLine.user("a", emptyList(), "watch #kick/destiny now", 1), Emotes.None)
        assertEquals(Embed("kick", "destiny"), assertIs<Segment.EmbedLink>(parts[1]).embed)
    }

    @Test
    fun readsTheLiveSocket() {
        val streams = assertIs<LiveUpdate.Streams>(
            parseLiveMessage("""{"type":"dggApi:streamInfo","data":{"streams":{"twitch":null,"youtube":{"live":true,"id":"vid1","status_text":"hi"},"kick":{"live":true,"id":"destiny"}}}}"""),
        )
        val embeds = assertIs<LiveUpdate.Embeds>(
            parseLiveMessage("""{"type":"dggApi:embeds","data":[{"platform":"kick","id":"a","count":3},{"platform":"youtube","id":"b","count":9,"mediaItem":{"metadata":{"title":"T","displayName":"D","live":true}}}]}"""),
        )
        assertEquals(listOf("youtube/b", "kick/a"), embeds.embeds.map { it.embed.key })
        val banned = assertIs<LiveUpdate.Banned>(parseLiveMessage("""{"type":"dggApi:bannedEmbeds","data":[{"platform":"kick","name":"Bad","reason":""}]}"""))
        val state = LiveState().with(streams).with(embeds).with(banned)
        // YouTube is bigscreen's default platform when Destiny is live on several.
        // Kick unless another platform is preferred.
        assertEquals(Embed("kick", "destiny"), state.defaultEmbed())
        assertEquals(Embed("youtube", "vid1"), state.defaultEmbed("youtube"))
        // Preferred but not live there: the first that is.
        assertEquals(Embed("youtube", "vid1"), state.defaultEmbed("rumble"))
        assertEquals(true, state.isBanned(Embed("kick", "bad")))
        assertNull(LiveState().defaultEmbed())
    }
}
