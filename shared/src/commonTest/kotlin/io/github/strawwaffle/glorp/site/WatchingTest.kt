package io.github.strawwaffle.glorp.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchingTest {
    @Test
    fun saysWhatPlaysAsBigscreenDoes() {
        assertEquals("""{"type":"watching","data":{"platform":"kick","id":"destiny"}}""", watchingMessage(Embed("kick", "destiny")))
        assertEquals("""{"type":"watching","data":null}""", watchingMessage(null))
    }

    @Test
    fun knowsWhenTheSocketIsSignedIn() {
        assertTrue(isMe("""{"type":"me","data":{"nick":"x"}}"""))
        assertFalse(isMe("""{"type":"dggApi:embeds","data":[]}"""))
        assertFalse(isMe("not json"))
    }
}
