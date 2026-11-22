package io.github.strawwaffle.glorp.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EmoteContextTest {
    // Written as the site's stylesheet writes them.
    private val css = """
        .emote.BLADE { background-image: url("blade.png"); height: 32px; width: 32px; }
        .emote.BLADE + .emote.BLADE, .emote.RapThis + .emote.BLADE { width: 40px; transform: scale(-1, 1); }
        .emote.BLADE:only-of-type, .emote + .emote.BLADE:last-of-type { transform-origin: 0% 100%; }
        .emote.BLADE + .emote.BLADE:hover { width: 99px; }
        .emote.KYSNOW { background-image: url("k.png"); height: 32px; width: 32px; }
        .msg-chat:has(.emote.BERN) .emote.KYSNOW { width: 36px; }
        .emote.BERN { background-image: url("b.png"); height: 30px; width: 30px; }
        .msg-chat .emote.BERN { margin-top: -30px; }
        .text > .emote.Chatting:not(.emote.SMASHit + .emote.Chatting) { height: 24px; }
        .emote.Chatting { background-image: url("c.png"); height: 32px; width: 32px; }
        .emote.SMASHit { background-image: url("s.png"); height: 32px; width: 32px; }
        .msg-chat.msg-emote[data-combo-group="x10"] .emote.EatFresh { --combo-img: url("fresh-combo.gif"); background-image: var(--combo-img); width: 64px; }
        .msg-chat.msg-emote[data-combo-group^="x"][data-combo-group*="0"][data-combo-group${'$'}="0"] .emote.EatFresh { height: 20px; }
        .emote.EatFresh { background-image: url("f.png"); height: 32px; width: 32px; }
        .msg-chat.msg-user .user.flair42 ~ .text .emote.BLADE { height: 30px; }
    """.trimIndent()

    private val json = listOf("BLADE", "KYSNOW", "BERN", "Chatting", "SMASHit", "EatFresh", "RapThis").joinToString(",", "[", "]") {
        """{"prefix":"$it","image":[{"url":"https://cdn.example/emotes/$it.png","width":32,"height":32}]}"""
    }
    private val emotes = Emotes.parse(json, css)

    private fun line(vararg parts: String?, features: List<String> = emptyList(), nameFlair: String? = null) =
        emotes.inLine(LineModel.message(features, nameFlair, continued = false, green = false, parts = parts.toList()))

    @Test
    fun anEmoteAloneIsItself() {
        assertSame(emotes["BERN"], line("BERN").single())
        // Alone, BLADE is the only one of its kind: it turns from its corner.
        assertEquals(0f, line("BLADE").single()!!.originX)
    }

    @Test
    fun neighboursChangeAnEmote() {
        val (first, second) = line("BLADE", "BLADE")
        assertEquals(32, first!!.width)
        assertEquals(40, second!!.width)
        // The last BLADE of a line turns from its corner; hover never applies.
        assertEquals(0f, second.originX)
        assertEquals(1f, second.originY)
        assertEquals(40, line("RapThis", "BLADE")[1]!!.width)
        // Mirrored to face the one before it.
        assertEquals(-1f, second.rest.sx)
        assertEquals(1f, first.rest.sx)
        // A link between them breaks the pair, as an element does in chat.
        assertEquals(32, line("BLADE", null, "BLADE")[2 - 1]!!.width)
    }

    @Test
    fun aLineHoldingAnEmoteChangesAnother() {
        assertEquals(36, line("KYSNOW", "BERN")[0]!!.width)
        assertEquals(32, line("KYSNOW")[0]!!.width)
    }

    @Test
    fun notAndFlairs() {
        assertEquals(24, line("Chatting").single()!!.height)
        assertEquals(32, line("SMASHit", "Chatting")[1]!!.height)
        assertEquals(30, line("BLADE", features = listOf("flair42"), nameFlair = "flair42").single()!!.height)
        assertEquals(32, line("BLADE", features = listOf("flair42"), nameFlair = null).single()!!.height)
    }

    @Test
    fun combosBySize() {
        val small = emotes.inLine(LineModel.combo("EatFresh", 6)).single()!!
        val big = emotes.inLine(LineModel.combo("EatFresh", 12)).single()!!
        assertEquals(32, small.width)
        assertEquals(64, big.width)
        assertEquals(20, big.height)
        assertEquals("https://cdn.example/emotes/fresh-combo.gif", big.url)
        assertNotEquals(small, big)
        assertTrue(emotes.inLine(LineModel.combo("EatFresh", 30)).single()!!.height == 20)
    }
}
