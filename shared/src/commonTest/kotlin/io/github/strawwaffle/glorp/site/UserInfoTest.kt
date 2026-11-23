package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.ui.joined
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserInfoTest {
    @Test
    fun readsTheSitesAnswer() {
        val info = parseUserInfo(
            """{"success":true,"message":null,"data":{"id":10,"nick":"Destiny","roles":["ADMIN"],"features":["moderator","flair14"],""" +
                """"createdDate":"2013-03-08T01:48:44+00:00","photo":"buddy","bio":null,"gender":"male","age":"25-34",""" +
                """"watching":{"platform":"kick","id":"destiny"}},"error":null}""",
        )!!
        assertEquals("Destiny", info.nick)
        assertEquals(listOf("moderator", "flair14"), info.features)
        assertEquals("male", info.gender)
        assertNull(info.bio)
        assertEquals(Embed("kick", "destiny"), info.watching)
        assertEquals("8 March 2013", joined(info.createdDate!!))
    }

    @Test
    fun aRefusalIsNothing() {
        assertNull(parseUserInfo("""{"success":false,"message":"nope","data":null}"""))
        assertNull(parseUserInfo("<html>"))
    }
}
