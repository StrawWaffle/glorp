package io.github.strawwaffle.glorp.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AngelThumpTest {
    private val media = "https://ams1.angelthump.com/hls/MjAy_beetlorddwight/index.m3u8"

    @Test
    fun movesAStreamBetweenEdges() {
        assertEquals("ams1.angelthump.com", AngelThumpEdges.hostOf(media))
        assertEquals("https://sgp1.angelthump.com/hls/MjAy_beetlorddwight/index.m3u8", AngelThumpEdges.onEdge(media, "sgp1.angelthump.com"))
        assertTrue(AngelThumpEdges.isEdge("fra1.angelthump.com"))
        assertFalse(AngelThumpEdges.isEdge("vigor.angelthump.com"))
    }

    @Test
    fun fallsBackToTheNearestEdges() {
        assertEquals(listOf("fra1", "nyc1", "sfo1", "sgp1").map { "$it.angelthump.com" }, AngelThumpEdges.backups("ams1.angelthump.com"))
        assertEquals("nyc1.angelthump.com", AngelThumpEdges.backups("sfo1.angelthump.com").first())
    }
}
