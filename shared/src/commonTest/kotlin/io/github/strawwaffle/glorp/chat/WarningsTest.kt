package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.site.readMutelinks
import io.github.strawwaffle.glorp.site.readNukes
import io.github.strawwaffle.glorp.site.readPhrases
import io.github.strawwaffle.glorp.site.readStamps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarningsTest {
    private val phrases = listOf(BannedPhrase("yky pepe", "mute", "10m"), BannedPhrase("/f[o0]+bar/", "ban", "1d"))
    private val nukes = listOf(Nuke("Glorp", "mute", "10m"))
    private val on = Mutelinks("on", "RiN_LuX", "10m")

    @Test
    fun trapsMatchAsTheUserscriptDoes() {
        assertTrue(Trap("YKY pepe").catches("lol yky PEPE"))
        assertTrue(Trap("/f[o0]+bar/").catches("F00BAR"))
        assertFalse(Trap("/f[o0]+bar/").catches("fbar"))
        // Text anywhere, not only whole words.
        assertTrue(Trap("glorp").catches("glorpy"))
        // A broken pattern is plain text.
        assertTrue(Trap("/a(b/").catches("x /a(b/ y"))
    }

    @Test
    fun phrasesComeFirstThenLinksThenNukes() {
        assertEquals(Warning.Kind.Phrase, checkDraft("yky pepe glorp", phrases, on, nukes)?.kind)
        assertEquals(Warning.Kind.Links, checkDraft("glorp https://x.com/a", phrases, on, nukes)?.kind)
        assertEquals(Warning.Kind.Nuke, checkDraft("GLORP", phrases, on, nukes)?.kind)
        assertEquals("1d", checkDraft("foobar", phrases, null, nukes)?.duration)
        assertNull(checkDraft("hello", phrases, on, nukes))
    }

    @Test
    fun linksOnlyWhileMutelinksIsOn() {
        assertEquals(Warning.Kind.Links, checkDraft("see example.com", emptyList(), on, emptyList())?.kind)
        assertNull(checkDraft("see example.com", emptyList(), Mutelinks("off", null, "10m"), emptyList()))
        assertNull(checkDraft("no link here.", emptyList(), on, emptyList()))
    }

    @Test
    fun whispersAndCommandsArentChecked() {
        assertNull(checkDraft("/w bob glorp", phrases, on, nukes))
        assertNull(checkDraft("/ignore glorp", phrases, on, nukes))
        assertEquals(Warning.Kind.Nuke, checkDraft("/me says glorp", phrases, on, nukes)?.kind)
    }

    @Test
    fun readsVyneerMe() {
        assertEquals(
            mapOf("mutelinks" to 1794962546748, "nukes" to 1795144303307),
            readStamps(chatJson.parseToJsonElement("""{"mutelinks":1794962546748,"nukes":1795144303307}""")),
        )
        assertEquals(
            listOf(Nuke("test", "meganuke", "10m")),
            readNukes(chatJson.parseToJsonElement("""{"data":[{"time":"2020-03-20T14:28:23","type":"meganuke","duration":"10m","word":"test"}],"updatedAt":1}""")),
        )
        assertEquals(
            listOf(BannedPhrase("yky pepe", "mute", "10m")),
            readPhrases(chatJson.parseToJsonElement("""{"data":[{"time":"x","username":"MrMouton","phrase":"yky pepe","duration":"10m","type":"mute"}],"updatedAt":1}""")),
        )
        assertEquals(
            Mutelinks("off", "RiN_LuX", "10m"),
            readMutelinks(chatJson.parseToJsonElement("""{"data":[{"duration":"10m","status":"off","time":"x","user":"RiN_LuX"}],"updatedAt":1}""")),
        )
        assertEquals(Mutelinks("off", null, ""), readMutelinks(chatJson.parseToJsonElement("""{"data":[],"updatedAt":1}""")))
    }
}
