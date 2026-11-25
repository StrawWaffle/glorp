package io.github.strawwaffle.glorp.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// In the shape of linkers.ooo's /api/media-links (2026-11-25), with made-up posts.
class LinkersTest {
    @Test
    fun readsAPage() {
        val body = """{"links":[
            {"id":11,"timestamp":"2026-11-25T09:40:14.000Z","user":"Kettle","message":"look https://example.com/a.jpg","user_flairs":["flair13","subscriber"],
             "url":"https://example.com/a.jpg","mediaType":"image","nsfw":false,"reactions":{"@":{"count":2,"users":["MoonPie","Rook"]},"HUH":{"count":5,"users":[]}}},
            {"id":12,"timestamp":"2026-11-25T09:32:29.000Z","user":"Bot","message":"a video","user_flairs":[],
             "url":"https://youtu.be/abcdefghijk","mediaType":"youtube","nsfw":true,"reactions":{}},
            {"id":13,"user":"NoTime","url":"https://example.com","mediaType":"image"}
        ],"limit":20,"offset":26,"hasMore":true}"""
        val page = parseLinksPage(body, asked = 0)!!
        assertEquals(26, page.next)
        assertEquals(true, page.hasMore)
        assertEquals(2, page.posts.size)
        val first = page.posts[0]
        assertEquals(1795599614000L, first.at)
        assertEquals(listOf("flair13", "subscriber"), first.flairs)
        assertEquals("image", first.kind)
        // Most used first.
        assertEquals(listOf("HUH", "@"), first.reactions.map { it.name })
        assertEquals(listOf("MoonPie", "Rook"), first.reactions[1].users)
        assertEquals(true, page.posts[1].nsfw)
    }

    @Test
    fun withoutAnOffsetTheNextPageFollowsThisOne() {
        assertEquals(40, parseLinksPage("""{"links":[],"hasMore":false}""", asked = 20)!!.next)
    }

    @Test
    fun readsReactionMessages() {
        val body = """{"messages":[{"id":1,"timestamp":"2026-11-25T09:41:19.000Z","user":"MoonPie","message":"Kettle HUH","message_type":"MSG","user_flairs":["flair1"]}]}"""
        assertEquals(listOf(ReactionMessage(1795599679000L, "MoonPie", listOf("flair1"), "Kettle HUH")), parseReactionMessages(body))
    }

    @Test
    fun rejectsOtherShapes() {
        assertNull(parseLinksPage("""{"error":"nope"}""", 0))
        assertNull(parseLinksPage("not json", 0))
        assertNull(parseReactionMessages("[]"))
    }

    @Test
    fun waitsAsTheHeadersSay() {
        val now = 1_795_600_000_000L
        assertEquals(now + 30_000, restUntil(now, retryAfter = 30, reset = null))
        // X-RateLimit-Reset as a time: linkers.ooo sends epoch seconds.
        assertEquals(now + 45_000, restUntil(now, retryAfter = null, reset = 1_795_600_045L))
        assertEquals(now + 20_000, restUntil(now, retryAfter = null, reset = 20))
        assertEquals(now + 60_000, restUntil(now, retryAfter = null, reset = null))
    }
}
