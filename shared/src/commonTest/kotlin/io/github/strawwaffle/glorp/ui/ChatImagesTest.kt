package io.github.strawwaffle.glorp.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatImagesTest {
    @Test
    fun failedPicturesAreTriedAgainSoonThenLessOften() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..8).map(ChatImages::retryDelayMs))
        assertEquals(60_000L, ChatImages.retryDelayMs(1_000))
    }

    @Test
    fun picturesAreKeyedByTheirFileNameAcrossSiteVersions() {
        assertEquals(
            ChatImages.cacheKey("https://cdn.destiny.gg/4.50.1/flairs/61952a6b4a85b.png"),
            ChatImages.cacheKey("https://stagingr2cdn.destiny.gg/flairs/61952a6b4a85b.png"),
        )
    }
}
