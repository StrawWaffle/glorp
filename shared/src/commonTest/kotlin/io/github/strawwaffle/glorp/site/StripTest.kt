package io.github.strawwaffle.glorp.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StripTest {
    // BINGQILIN as the site writes it (2026-11): keyframes through variables
    // each emote fills in, and a resting position partway along the strip.
    private val css = """
        .emote.BINGQILIN { background-image: url("691926a52c4e6.avif"); height: 32px; width: 6390px; }
        .msg-chat .emote.BINGQILIN { margin-top: -32px; top: 8px; }
        @keyframes BINGQILIN {
          0% { background-position: var(--start); }
          100% { background-position: var(--end); }
        }
        .emote.BINGQILIN {
          --start: 0px;
          --end: -2880px;
          width: 32px;
          background-position: -2848px;
          animation: BINGQILIN steps(90) 3s 1;
        }
        .emote.OLD { background-image: url("old.png"); height: 32px; width: 32px; animation: OLD-anim 1s steps(4) 2; }
        @keyframes OLD-anim { 0% { background-position: 0px; } 100% { background-position: -128px; } }
    """.trimIndent()

    private val json = """[{"prefix":"BINGQILIN","image":[{"url":"https://cdn.example/emotes/691926a52c4e6.avif","width":6390,"height":32}]},""" +
        """{"prefix":"OLD","image":[{"url":"https://cdn.example/emotes/old.png","width":160,"height":32}]}]"""

    private val emotes = Emotes.parse(json, css)

    @Test
    fun keyframesThroughVariablesMoveTheStrip() {
        val e = emotes["BINGQILIN"]!!
        assertEquals(32, e.width)
        assertEquals(2848, e.offsetX)
        val sprite = assertNotNull(e.sprite)
        assertEquals(90, sprite.frames)
        assertEquals(3000, sprite.durationMs)
        assertEquals(0, sprite.startX)
    }

    @Test
    fun plainKeyframesStillWork() {
        val sprite = assertNotNull(emotes["OLD"]!!.sprite)
        assertEquals(4, sprite.frames)
        assertEquals(2, sprite.iterations)
        assertEquals(0, sprite.startX)
    }
}
