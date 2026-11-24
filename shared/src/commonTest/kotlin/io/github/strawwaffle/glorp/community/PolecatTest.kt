package io.github.strawwaffle.glorp.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// In the shape of polecat.me's /api/mentions/<nick> (2026-11-24), with made-up messages.
class PolecatTest {
    @Test
    fun readsMentions() {
        val body = """[
            {"id":11,"date":1795504220000,"nick":"someone","flairs":"","text":"Glorpy hello"},
            {"id":12,"date":1795504349000,"nick":"Bot","flairs":"protected,bot","text":"Glorpy a link"},
            {"id":13,"nick":"nodate","text":"skipped"}
        ]"""
        val lines = parsePolecat(body)!!
        assertEquals(2, lines.size)
        assertEquals(PolecatLine(11, 1795504220000, "someone", emptyList(), "Glorpy hello"), lines[0])
        assertEquals(listOf("protected", "bot"), lines[1].flairs)
        assertEquals(LogLine(1795504349000, "Bot", "Glorpy a link"), lines[1].logLine())
    }

    @Test
    fun rejectsOtherShapes() {
        assertNull(parsePolecat("""{"error":"nope"}"""))
        assertNull(parsePolecat("not json"))
    }
}
