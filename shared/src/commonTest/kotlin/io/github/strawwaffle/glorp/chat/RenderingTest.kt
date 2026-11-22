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

class EmoteSizesAndCombosTest {
    private val json = """[
      {"prefix":"BlueWave","twitch":false,"minimumSubTier":0,"image":[{"url":"https://cdn/w.gif","height":500,"width":500}]},
      {"prefix":"glorp","twitch":false,"minimumSubTier":0,"image":[{"url":"https://cdn/g.png","height":28,"width":308}]},
      {"prefix":"Chatting","twitch":false,"minimumSubTier":0,"image":[{"url":"https://cdn/c.png","height":32,"width":320}]},
      {"prefix":"Huge","twitch":false,"minimumSubTier":0,"image":[{"url":"https://cdn/h.png","height":400,"width":400}]},
      {"prefix":"AYAYA","twitch":false,"minimumSubTier":0,"image":[{"url":"https://cdn/a.png","height":27,"width":28}]}
    ]"""
    // Trimmed from destiny.gg's emotes.css.
    private val css = """
      .emote.BlueWave { background-image: url("x.gif"); height: 500px; width: 500px; }
      .msg-chat .emote.BlueWave { margin-top: -500px; top: 125px; }
      .emote.BlueWave { --size: 32px; width: var(--size); height: var(--size); background-size: var(--size) var(--size); background-repeat: no-repeat; background-position: center; }
      .emote.glorp { background-image: url("g.png"); height: 28px; width: 308px; }
      .emote.glorp { height: 28px; width: 28px; background-position: 0px; background-repeat: no-repeat; }
      [data-username="destiny"] .emote.glorp { background-position: -110px; }
      .emote.Chatting { background-image: url("c.png"); height: 32px; width: 320px; }
      .text > .emote.Chatting { width: 32px; height: 32px; transform: scale(-1, 1) translate(-2px, 3px); }
      .emote.Huge { height: 400px; width: 400px; }
    """
    private val emotes = Emotes.parse(json, css)

    @Test
    fun cssVariablesAndBackgroundSizeShrinkAPicture() {
        val wave = assertNotNull(emotes["BlueWave"])
        assertEquals(32, wave.width)
        assertEquals(32, wave.height)
        assertEquals(true, wave.isWholePicture)
    }

    @Test
    fun stripsShowOneSlice() {
        val glorp = assertNotNull(emotes["glorp"])
        assertEquals(28, glorp.width)
        assertEquals(308, glorp.sheetWidth)
        assertEquals(0, glorp.offsetX)
        assertEquals(false, glorp.isWholePicture)
        assertEquals(32, assertNotNull(emotes["Chatting"]).width)
    }

    @Test
    fun noEmoteIsBiggerThanALine() {
        val huge = assertNotNull(emotes["Huge"])
        assertEquals(64, huge.height)
        assertEquals(64, huge.width)
    }

    @Test
    fun runsOfOneEmoteBecomeACombo() {
        val log = ChatLog()
        val combo: (ChatLine) -> String? = { soleEmote(it, emotes)?.prefix }
        log.add(ChatLine.user("a", emptyList(), "hello", 1), combo)
        log.add(ChatLine.user("b", emptyList(), "AYAYA", 2), combo)
        log.add(ChatLine.user("c", emptyList(), " AYAYA ", 3), combo)
        log.add(ChatLine.user("d", emptyList(), "AYAYA", 4), combo)
        var lines = log.snapshot()
        assertEquals(2, lines.size)
        assertEquals(ChatLine.Kind.Combo, lines[1].kind)
        assertEquals(3, lines[1].comboCount)
        val comboId = lines[1].id
        // The backlog on reconnect repeats them: still one combo of three.
        log.add(ChatLine.user("c", emptyList(), " AYAYA ", 3), combo)
        assertEquals(3, log.snapshot()[1].comboCount)
        // Anything else ends it; the next AYAYA starts afresh.
        log.add(ChatLine.user("e", emptyList(), "nice", 5), combo)
        log.add(ChatLine.user("f", emptyList(), "AYAYA", 6), combo)
        lines = log.snapshot()
        assertEquals(listOf(ChatLine.Kind.User, ChatLine.Kind.Combo, ChatLine.Kind.User, ChatLine.Kind.User), lines.map { it.kind })
        assertEquals(comboId, lines[1].id)
        // An emote with words around it isn't a combo.
        log.add(ChatLine.user("g", emptyList(), "AYAYA lol", 7), combo)
        assertEquals(5, log.snapshot().size)
    }
}
