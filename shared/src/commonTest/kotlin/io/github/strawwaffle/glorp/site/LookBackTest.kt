package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.community.LogLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LookBackTest {
    @Test
    fun readsTheSitesStalkAndMentions() {
        assertEquals(
            listOf(LogLine(1_795_401_620_000, "strawwaffle", "hi")),
            parseStalk("""{"success":true,"data":{"nick":"strawwaffle","lines":[{"timestamp":1795401620,"text":"hi"}]}}"""),
        )
        assertEquals(
            listOf(LogLine(1_795_401_620_000, "Pal", "glorp you there")),
            parseSiteMentions("""{"success":true,"data":[{"date":1795401620,"nick":"Pal","text":"glorp you there"}]}"""),
        )
        assertNull(parseStalk("""{"success":false}"""))
        assertEquals(
            listOf(LogLine(5, "Pal", "hey")),
            parseServiceMentions("""{"mentions":[{"nick":"Pal","text":"hey","at":5}]}"""),
        )
    }

    @Test
    fun knowsVideoLinksInTheirSpellings() {
        val keys = listOf(
            "https://www.youtube.com/watch?v=4t4AQ15yj1M",
            "https://youtu.be/4t4AQ15yj1M?t=30",
            "https://m.youtube.com/watch?feature=share&v=4t4AQ15yj1M",
            "https://www.youtube.com/shorts/4t4AQ15yj1M",
        ).map { mediaOf(it)?.key }
        assertEquals(List(4) { "youtube:4t4AQ15yj1M" }, keys)
        assertEquals("https://rumble.com/v2v5pki-diablo-iv.html", mediaOf("https://rumble.com/v2v5pki-diablo-iv.html?e9s=src")?.page)
        assertNull(mediaOf("https://rumble.com/c/Destiny"))
        assertNull(mediaOf("https://example.com/watch?v=4t4AQ15yj1M"))
    }

    @Test
    fun readsOembed() {
        assertEquals(
            LinkTitles.Title("lofi", "Lofi Girl", "youtube"),
            parseOembed("""{"title":"lofi","author_name":"Lofi Girl"}""", "youtube"),
        )
        assertNull(parseOembed("""{"author_name":"x"}""", "rumble"))
    }
}
