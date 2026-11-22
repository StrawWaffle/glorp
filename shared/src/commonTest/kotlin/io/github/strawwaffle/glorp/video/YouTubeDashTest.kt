package io.github.strawwaffle.glorp.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeDashTest {
    private fun source(itag: Int, container: String, codec: String, original: Boolean = false, height: Int? = null) = YtSource(
        url = "https://rr1---sn-x.googlevideo.com/videoplayback?itag=$itag&a=1&b=2",
        width = height?.let { it * 16 / 9 }, height = height,
        container = container, codec = codec, bitrate = 1000L * itag, duration = 214, itag = itag,
        initStart = 0, initEnd = 700, indexStart = 701, indexEnd = 1200, original = original,
        headers = mapOf("TE" to "trailers", "User-Agent" to "client/1"),
    )

    private val details = YtDetails(
        name = "A recording",
        sources = listOf(source(137, "video/mp4", "avc1.640028", height = 1080), source(248, "video/webm", "vp9", height = 1080)),
        audio = listOf(source(140, "audio/mp4", "mp4a.40.2"), source(251, "audio/webm", "opus", original = true), source(250, "audio/webm", "opus")),
    )

    @Test
    fun aManifestHasEachContainerAsItsOwnSet() {
        val mpd = ytDashManifest(details)!!
        assertEquals(3, Regex("<AdaptationSet").findAll(mpd).count())
        assertTrue("""mediaPresentationDuration="PT214S"""" in mpd)
        assertTrue("""<SegmentBase indexRange="701-1200"><Initialization range="0-700"/></SegmentBase>""" in mpd)
        assertTrue("""width="1920" height="1080"""" in mpd)
        // Addresses are escaped for XML.
        assertTrue("itag=137&amp;a=1&amp;b=2" in mpd)
    }

    @Test
    fun onlyTheOriginalAudioIsKeptWhenThereAreDubs() {
        val mpd = ytDashManifest(details)!!
        assertTrue("itag=251" in mpd)
        assertTrue("itag=140" !in mpd && "itag=250" !in mpd)
    }

    @Test
    fun noManifestWithoutBothVideoAndAudio() {
        assertNull(ytDashManifest(details.copy(audio = emptyList())))
        assertNull(ytDashManifest(details.copy(sources = details.sources.map { it.copy(indexEnd = null) })))
    }

    @Test
    fun headersDropTe() {
        assertEquals(mapOf("User-Agent" to "client/1"), ytHeaders(details))
    }

    @Test
    fun readsThePluginsAnswer() {
        val d = parseYtDetails("""{"name":"x","isLive":false,"live":null,"sources":[{"type":"VideoUrlRangeSource","url":"u","width":null,"itag":1,"headers":{}}],"audio":[],"extra":1}""")
        assertEquals("x", d.name)
        assertEquals(1, d.sources.single().itag)
    }
}
