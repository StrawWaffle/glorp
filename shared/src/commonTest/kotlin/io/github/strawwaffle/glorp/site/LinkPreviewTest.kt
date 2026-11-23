package io.github.strawwaffle.glorp.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkPreviewTest {
    @Test
    fun picksThePreviewAsChatGuiDoes() {
        assertEquals(PreviewKind.Video, previewKind("https://youtu.be/4t4AQ15yj1M", false))
        assertEquals(PreviewKind.Video, previewKind("https://rumble.com/v2v5pki-diablo.html", false))
        assertEquals(PreviewKind.Post("1234567890"), previewKind("https://x.com/someone/status/1234567890?s=20", false))
        assertEquals(PreviewKind.Post("20"), previewKind("https://twitter.com/jack/status/20", false))
        // Imgur pages point at their thumbnail; direct pictures on known hosts as they are.
        assertEquals(PreviewKind.Picture("https://i.imgur.com/AbC123xm.jpg"), previewKind("https://imgur.com/AbC123x", false))
        assertEquals(PreviewKind.Picture("https://i.imgur.com/AbC123x.png"), previewKind("https://i.imgur.com/AbC123x.png", false))
        assertEquals(PreviewKind.Picture("https://kappa.lol/xyz12"), previewKind("https://kappa.lol/xyz12", false))
        // Other sites' pictures only with "preview pictures from any site"; never over plain http.
        assertNull(previewKind("https://example.com/cat.jpg", false))
        assertEquals(PreviewKind.Picture("https://example.com/cat.jpg"), previewKind("https://example.com/cat.jpg", true))
        assertNull(previewKind("http://i.imgur.com/AbC123x.png", true))
        assertNull(previewKind("https://example.com/page", true))
    }

    @Test
    fun readsTheMessagesFlag() {
        assertEquals(LinkFlag.Nsfw, linkFlag("nsfw https://i.imgur.com/a.png"))
        assertEquals(LinkFlag.Nsfl, linkFlag("NSFL and nsfw"))
        assertEquals(LinkFlag.Spoilers, linkFlag("spoilers for the finale"))
        assertNull(linkFlag("nsfwish"))
    }

    @Test
    fun readsAnXPost() {
        val post = parseXPost(
            """{"code":200,"tweet":{"text":"hello","author":{"name":"Jack","screen_name":"jack"},
               "media":{"all":[{"type":"video","url":"https://video.twimg.com/v.mp4","thumbnail_url":"https://pbs.twimg.com/t.jpg"}]}}}""",
        )
        assertEquals(LinkPreview.Post("Jack", "jack", "hello", "https://pbs.twimg.com/t.jpg"), post)
        assertNull(parseXPost("""{"code":404,"message":"NOT_FOUND"}"""))
    }
}
