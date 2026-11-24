package io.github.strawwaffle.glorp.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RumbleTest {
    private val live = """
        {"fps":30,"w":1920,"h":1080,
         "u":{"hls":{"url":"https://rumble.com/live-hls-dvr/AbCdEf/playlist.m3u8","meta":{"live":true}}},
         "ua":{"mp4":[],"webm":[],
           "hls":{"auto":{"url":"https://rumble.com/live-hls-dvr/AbCdEf/playlist.m3u8","meta":{"live":true}},
                  "1081":{"url":"https://rumble.com/live-hls-dvr/AbCdEf/playlist-original.m3u8","meta":{"w":1920,"h":1080,"bitrate":8000}},
                  "480":{"url":"https://rumble.com/live-hls-dvr/AbCdEf/playlist-480p.m3u8","meta":{"w":852,"h":480,"bitrate":2100}}}},
         "title":"Building a shed &amp; a fence","author":{"name":"Someone"},"duration":0,"live":2,"livestream_has_dvr":true}
    """.trimIndent()

    private val recording = """
        {"u":{"tar":{"url":"https://cdn.example.rumble.cloud/video/a.caa.rec.tar?r_file=chunklist.m3u8","meta":{"w":854,"h":480}},
              "timeline":{"url":"https://cdn.example.rumble.cloud/video/a.Faa.rec.mp4","meta":{"w":320,"h":180}},
              "hls":{"url":"https://rumble.com/hls-vod/XyZ123/playlist.m3u8"}},
         "ua":{"tar":{"360":{"url":"https://cdn.example.rumble.cloud/video/a.baa.rec.tar","meta":{"w":640,"h":360}}},
               "timeline":{"180":{"url":"https://cdn.example.rumble.cloud/video/a.Faa.rec.mp4","meta":{"w":320,"h":180}}},
               "hls":{"auto":{"url":"https://rumble.com/hls-vod/XyZ123/playlist.m3u8"},
                      "720":{"url":"https://rumble.com/hls-vod/XyZ123/playlist-720.m3u8","meta":{"w":1280,"h":720}}}},
         "title":"They&apos;re &quot;fine&quot;","duration":600,"live":0}
    """.trimIndent()

    private val oldUpload = """
        {"u":{"mp4":{"url":"https://cdn.example.rumble.cloud/video/old.baa.mp4","meta":{"w":640,"h":360}}},
         "ua":{"mp4":{"360":{"url":"https://cdn.example.rumble.cloud/video/old.baa.mp4","meta":{"h":360}},
                      "720":{"url":"https://cdn.example.rumble.cloud/video/old.gaa.mp4","meta":{"h":720}},
                      "2160":{"url":"https://cdn.example.rumble.cloud/video/old.4k.mp4","meta":{"h":2160}}}},
         "title":"Old upload","duration":90,"live":0}
    """.trimIndent()

    @Test
    fun aLiveStreamPlaysWithoutItsPast() {
        val p = assertIs<Resolution.Native>(rumbleStream(live, startSeconds = 30)).playable
        assertEquals("https://rumble.com/live-hls/AbCdEf/playlist.m3u8", p.hlsUrl)
        assertEquals(true, p.live)
        assertEquals(Playable.Format.Hls, p.format)
        assertEquals("Building a shed & a fence", p.title)
        assertNull(p.startSeconds)
    }

    @Test
    fun aRecordingPlaysItsPlaylistOfEveryQuality() {
        val p = assertIs<Resolution.Native>(rumbleStream(recording, startSeconds = 42)).playable
        assertEquals("https://rumble.com/hls-vod/XyZ123/playlist.m3u8", p.hlsUrl)
        assertEquals(false, p.live)
        assertEquals(42, p.startSeconds)
        assertEquals("They're \"fine\"", p.title)
    }

    @Test
    fun anOldUploadPlaysItsBestMp4UpTo1080p() {
        val p = assertIs<Resolution.Native>(rumbleStream(oldUpload)).playable
        assertEquals("https://cdn.example.rumble.cloud/video/old.gaa.mp4", p.hlsUrl)
        assertEquals(Playable.Format.Mp4, p.format)
    }

    @Test
    fun anythingElseUsesTheWebPlayer() {
        assertEquals(Resolution.Web, rumbleStream("false"))
        assertEquals(Resolution.Web, rumbleStream("""{"title":"Starts soon","live":1,"ua":{"mp4":[],"hls":[]}}"""))
    }

    @Test
    fun onlyRumblesOwnLivePathIsRewritten() {
        assertEquals("https://rumble.com/hls-vod/X/playlist.m3u8", withoutDvr("https://rumble.com/hls-vod/X/playlist.m3u8"))
    }
}
