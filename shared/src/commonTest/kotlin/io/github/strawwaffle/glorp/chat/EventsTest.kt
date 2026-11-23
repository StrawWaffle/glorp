package io.github.strawwaffle.glorp.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventsTest {
    private val now = { 1_000L }

    private fun line(frame: String) = assertIs<ChatEvent.Message>(parseFrame(frame, now)).line

    @Test
    fun readsPaidEventsWithChatGuisWords() {
        val sub = line("""SUBSCRIPTION {"user":{"nick":"Pal","features":["flair3"]},"tier":2,"tierLabel":"Tier II","streak":4,"data":"hi PEPE","timestamp":5,"uuid":"u1"}""")
        assertEquals(ChatLine.Kind.Event, sub.kind)
        assertEquals("Pal", sub.nick)
        assertEquals("is now a Tier II subscriber · 4 month streak", sub.event!!.header)
        assertEquals("hi PEPE", sub.text)

        assertEquals("gifted Friend a Tier I subscription",
            line("""GIFTSUB {"user":{"nick":"Pal"},"recipient":{"nick":"Friend"},"tier":1,"tierLabel":"Tier I","timestamp":5}""").event!!.header)
        assertEquals("gifted 5 Tier I subs to the community",
            line("""MASSGIFT {"user":{"nick":"Pal"},"tier":1,"tierLabel":"Tier I","quantity":5,"timestamp":5}""").event!!.header)
        val donation = line("""DONATION {"user":{"nick":"Pal"},"amount":123450,"data":"for the stream","timestamp":5}""")
        assertEquals("donated $1,234.50", donation.event!!.header)
        assertEquals("for the stream", donation.text)
    }

    @Test
    fun readsDeathsPostsAndTheVestaboard() {
        val death = line("""DEATH {"nick":"Pal","data":"Pal died of dysentery","timestamp":5}""")
        assertNull(death.nick)
        assertEquals("Pal died of dysentery", death.event!!.header)
        val post = line("""XPOST {"handle":"destiny","text":"new video","url":"https://x.com/destiny/status/1","timestamp":5}""")
        assertEquals("@destiny posted on X", post.event!!.header)
        assertEquals("https://x.com/destiny/status/1", post.event!!.link)
        assertEquals("No designs on the Vestaboard yet", line("""VESTABOARD_HOURLY {"total":0,"timestamp":5}""").event!!.header)
        assertEquals("is leading the Vestaboard · $25.00", line("""VESTABOARD_HOURLY {"submitter":"Pal","total":2500,"timestamp":5}""").event!!.header)
    }

    @Test
    fun aBacklogReplaysTheSameEventLine() {
        val frame = """DONATION {"user":{"nick":"Pal"},"amount":500,"timestamp":5,"uuid":"same"}"""
        assertEquals(line(frame).id, line(frame).id)
    }

    @Test
    fun moderationGivesANoticeAndCensors() {
        val mute = assertIs<ChatEvent.Moderation>(parseFrame("""MUTE {"nick":"Mod","data":"Pal","timestamp":5}""", now))
        assertEquals("Pal", mute.removed)
        assertEquals("Pal muted by Mod.", mute.notice.text)
        assertEquals("Pal has been unmuted.", assertIs<ChatEvent.Moderation>(parseFrame("""UNMUTE {"nick":"System","data":"Pal"}""", now)).notice.text)
        val phrase = assertIs<ChatEvent.Moderation>(parseFrame("""ADDPHRASE {"nick":"Mod","data":"bad words"}""", now))
        assertNull(phrase.removed)
        assertEquals("Phrase \"bad words\" added.", phrase.notice.text)
        // The backlog carries them too, and who to censor.
        val history = assertIs<ChatEvent.History>(parseFrame("HISTORY " + """["MUTE {\"nick\":\"Mod\",\"data\":\"Pal\",\"timestamp\":5}"]""", now))
        assertEquals(listOf("Pal"), history.removed)
        assertEquals("Pal muted by Mod.", history.lines.single().text)
    }

    @Test
    fun spotlightsFrameTheirMessage() {
        val log = ChatLog()
        val msg = ChatLine.user("Pal", emptyList(), "look at this", 42)
        log.add(msg)
        val spot = assertIs<ChatEvent.Spotlight>(
            parseFrame("""SPOTLIGHT {"key":"k1","nick":"Pal","data":"look at this","messageTimestamp":42,"spotlightedBy":"Mod"}""", now),
        ).spot
        assertTrue(log.spotlight(spot))
        assertEquals("Mod", log.snapshot().single().spotlightedBy)
        assertTrue(log.unspotlight("k1"))
        assertNull(log.snapshot().single().spotlightedBy)
        // The list on connect comes before the backlog: marked once the message arrives.
        val fresh = ChatLog()
        fresh.setSpotlights(listOf(spot))
        fresh.add(msg)
        assertTrue(fresh.respotlight())
        assertEquals("Mod", fresh.snapshot().single().spotlightedBy)
    }
}
