package io.github.strawwaffle.glorp.site

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountTest {
    private val a = Account("Alice", emptyList(), 0, userId = "1")
    private val b = Account("Bob", emptyList(), 0, userId = "2")

    @Test
    fun theSameAccountSignedInAgainKeepsItsThings() {
        assertFalse(accountChanged(a, a.copy(features = listOf("flair3")), "Alice"))
        // Chat's ME doesn't carry the user id: the name decides.
        assertFalse(accountChanged(a, a.copy(nick = "alice", userId = null), "Alice"))
        // Launch: nobody in memory yet, the stored name is this one.
        assertFalse(accountChanged(null, a, "alice"))
        assertFalse(accountChanged(null, a, null))
        assertFalse(accountChanged(null, null, null))
    }

    @Test
    fun anotherAccountOrSigningOutDropsTheLastOnesThings() {
        assertTrue(accountChanged(a, b, "Alice"))
        assertTrue(accountChanged(a, null, "Alice"))
        // Another provider's account under the same name is still someone else.
        assertTrue(accountChanged(a, a.copy(userId = "3"), "Alice"))
        assertTrue(accountChanged(a, b.copy(userId = null), "Alice"))
        // Offline at launch, then signed in as someone else.
        assertTrue(accountChanged(null, b, "Alice"))
    }
}
