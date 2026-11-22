package io.github.strawwaffle.glorp.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    @Test
    fun comparesVersions() {
        assertTrue(isNewer("0.2.0", "0.1.0"))
        assertTrue(isNewer("0.10.0", "0.9.3"))
        assertTrue(isNewer("1.0", "0.99.99"))
        assertFalse(isNewer("0.1.0", "0.1.0"))
        assertFalse(isNewer("0.1.0", "0.2.0"))
        assertFalse(isNewer("0.1.0", "0.1.0-dev"))
    }

    @Test
    fun readsAReleaseAndItsDigest() {
        val digest = "a".repeat(64)
        val json = """{"tag_name":"v0.2.0","html_url":"https://github.com/StrawWaffle/glorp/releases/tag/v0.2.0","draft":false,"prerelease":false,
            "assets":[{"name":"glorp-0.2.0.apk.sha256","browser_download_url":"x"},
                      {"name":"glorp-0.2.0.apk","browser_download_url":"https://example/glorp-0.2.0.apk","digest":"sha256:$digest"}]}"""
        val release = readRelease(json)!!
        assertEquals("0.2.0", release.version)
        assertEquals("https://example/glorp-0.2.0.apk", release.apkUrl)
        assertEquals(digest, release.sha256)
    }

    @Test
    fun skipsPreReleasesAndReleasesWithoutAnApk() {
        assertNull(readRelease("""{"tag_name":"v1","prerelease":true,"assets":[{"name":"glorp-1.apk","browser_download_url":"x"}]}"""))
        assertNull(readRelease("""{"tag_name":"v1","assets":[]}"""))
    }
}
