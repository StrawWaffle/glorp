package io.github.strawwaffle.glorp.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignInCodeTest {
    private val site = Site.guess("https://www.destiny.gg")

    @Test
    fun readsTheComputerPagesCode() {
        val cookies = readSignInCode("glorp-signin:1:destiny.gg:abc123:def456", site)!!
        assertEquals("abc123", cookies.sid)
        assertEquals("def456", cookies.rememberMe)
        assertEquals(null, readSignInCode("glorp-signin:1:destiny.gg:abc123:", site)!!.rememberMe)
        // A code for the test site doesn't sign in here.
        assertNull(readSignInCode("glorp-signin:1:omniliberal.dev:abc123:def456", site))
        assertNull(readSignInCode("hello", site))
    }
}
