package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.Flairs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RenderingTest {
    // Trimmed from the site's emotes.json and emotes.css.
    private val json = """[
      {"prefix":"Askers","twitch":false,"minimumSubTier":0,"image":[{"url":"https://cdn/askers.png","height":34,"width":5544}]},
      {"prefix":"Aware","twitch":false,"minimumSubTier":0,"image":[{"url":"https://cdn/aware.png","height":32,"width":1152}]},
      {"prefix":"AYAYA","twitch":false,"minimumSubTier":0,"image":[{"url":"https://cdn/ayaya.png","height":27,"width":28}]},
      {"prefix":"SubOnly","twitch":false,"minimumSubTier":5,"image":[{"url":"https://cdn/sub.png","height":30,"width":30}]},
      {"prefix":"TwitchOnly","twitch":true,"minimumSubTier":0,"image":[{"url":"https://cdn/t.png","height":30,"width":30}]}
    ]"""
    private val css = """
      .emote.Askers { background-image: url("604fa56f679e0.png"); height: 34px; width: 5544px; }
      .msg-chat .emote.Askers { margin-top: -34px; }
      /* a comment { with braces } */
      .emote.Askers { width: 56px; background-position: 0px; animation: Askers-anim calc(3.5s / var(--animation-speed, 1)) steps(99) 4; }
      @keyframes Askers-anim { 100% { background-position: -5544px; } }
      .emote.Aware { background-image: url("x.png"); height: 32px; width: 1152px; }
      .emote.Aware { height: 32px; width: 36px; animation: Aware-anim 1.3s steps(31) forwards; }
      @keyframes Aware-anim { to { background-position: -1116px; } }
      .emote.AYAYA { animation: AYAYA-anim 0.7s 1; }
      @keyframes AYAYA-anim { 25% { transform: translate(0px, -7.5px); } }
    """
    private val emotes = Emotes.parse(json, css)

    @Test
    fun readsSpriteStrips() {
        val askers = assertNotNull(emotes["Askers"])
        assertEquals(56, askers.width)
        val sprite = assertNotNull(askers.sprite)
        assertEquals(99, sprite.frames)
        assertEquals(3500L, sprite.durationMs)
        assertEquals(4, sprite.iterations)
        assertEquals(false, sprite.holdLast)

        val aware = assertNotNull(emotes["Aware"]?.sprite)
        assertEquals(31, aware.frames)
        assertEquals(1, aware.iterations)
        assertEquals(true, aware.holdLast)
    }

    @Test
    fun otherAnimationsStayStill() {
        val ayaya = assertNotNull(emotes["AYAYA"])
        assertNull(ayaya.sprite)
        assertEquals(28, ayaya.width)
        assertNull(emotes["TwitchOnly"])
    }

    @Test
    fun emotesAreWholeWordsTheSenderMayUse() {
        val line = ChatLine.user("a", emptyList(), "AYAYA hi AYAYA! SubOnly", 1)
        val parts = segments(line, emotes)
        assertIs<Segment.EmoteRef>(parts[0])
        assertEquals(" hi AYAYA! SubOnly", (parts[1] as Segment.Text).text)

        val subscriber = ChatLine.user("a", emptyList(), "SubOnly", 1, subTier = 5)
        assertIs<Segment.EmoteRef>(segments(subscriber, emotes).single())
        val moderator = ChatLine.user("a", listOf("moderator"), "SubOnly", 1)
        assertIs<Segment.EmoteRef>(segments(moderator, emotes).single())
    }

    @Test
    fun findsLinks() {
        assertEquals("https://destiny.gg/bigscreen", linkFor("https://destiny.gg/bigscreen"))
        assertEquals("https://youtube.com/watch?v=x", linkFor("youtube.com/watch?v=x,"))
        assertNull(linkFor("hello"))
        assertNull(linkFor("e.g."))
        val parts = segments(ChatLine.user("a", emptyList(), "see www.example.com now", 1), Emotes.None)
        assertEquals("https://www.example.com", (parts[1] as Segment.Link).url)
    }

    @Test
    fun badgesFollowPriorityAndSkipHiddenFlairs() {
        val flairs = Flairs.parse(
            """[
              {"name":"flair3","label":"Tier 2","hidden":false,"priority":5,"image":[{"url":"https://cdn/t2.png","width":16,"height":16}]},
              {"name":"flair8","label":"Tier 4","hidden":false,"priority":2,"image":[{"url":"https://cdn/t4.png","width":16,"height":16}]},
              {"name":"admin","label":"Admin","hidden":true,"priority":1,"image":[{"url":"https://cdn/a.png","width":16,"height":16}]},
              {"name":"flair120","label":"Head Mod","hidden":false,"priority":1,"image":[{"url":null}]}
            ]""",
        )
        assertEquals(listOf("Tier 4", "Tier 2"), flairs.badges(listOf("flair3", "admin", "flair8", "flair120")).map { it.label })
    }
}
