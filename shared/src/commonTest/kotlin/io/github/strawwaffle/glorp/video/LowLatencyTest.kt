package io.github.strawwaffle.glorp.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LowLatencyTest {
    // Shaped like a Kick (Amazon IVS) live playlist.
    private val ivs = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:10398
        #EXT-X-PROGRAM-DATE-TIME:2026-11-23T01:16:12.053Z
        #EXTINF:2.000,live
        https://cdn.example/a.ts
        #EXT-X-PROGRAM-DATE-TIME:2026-11-23T01:16:14.053Z
        #EXTINF:2.000,live
        https://cdn.example/b.ts
        #EXT-X-PREFETCH:https://cdn.example/c.ts
        #EXT-X-PREFETCH:https://cdn.example/d.ts
    """.trimIndent()

    @Test
    fun prefetchedSegmentsBecomeSegments() {
        val out = lowLatencyPlaylist(ivs).lines()
        assertTrue("#EXT-X-TARGETDURATION:2" in out)
        val c = out.indexOf("https://cdn.example/c.ts")
        assertEquals("#EXTINF:2.0,live", out[c - 1])
        assertEquals("#EXTINF:2.0,live", out[out.indexOf("https://cdn.example/d.ts") - 1])
        assertTrue(out.none { it.startsWith("#EXT-X-PREFETCH") })
        // Segments come in order, the prefetched ones last.
        assertTrue(out.indexOf("https://cdn.example/b.ts") < c)
    }

    @Test
    fun twitchsTagToo() {
        val twitch = ivs.replace("#EXT-X-PREFETCH:", "#EXT-X-TWITCH-PREFETCH:")
        assertEquals(4, lowLatencyPlaylist(twitch).lines().count { it.startsWith("https://") })
    }

    @Test
    fun aTargetThatFitsIsKept() {
        val youtube = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5.005,\nhttps://cdn.example/s.ts\n"
        assertTrue("#EXT-X-TARGETDURATION:5" in lowLatencyPlaylist(youtube).lines())
        // Never below the longest segment: 5.005 rounds up to 6.
        assertTrue("#EXT-X-TARGETDURATION:6" in lowLatencyPlaylist(youtube.replace("TARGETDURATION:5", "TARGETDURATION:10")).lines())
    }

    @Test
    fun multivariantPlaylistsAreLeftAlone() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/720.m3u8\n"
        assertSame(master, lowLatencyPlaylist(master))
    }
}
