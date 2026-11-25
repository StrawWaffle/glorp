package io.github.strawwaffle.glorp.update

import io.github.strawwaffle.glorp.chat.MemoryChatPrefs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateRouteTest {
    private val digest = "b".repeat(64)

    /** GitHub's API out of requests for this address; github.com itself still answers. */
    private val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        when {
            request.url.host == "api.github.com" -> respond("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden)
            path == "/StrawWaffle/glorp/releases/latest" ->
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://github.com/StrawWaffle/glorp/releases/tag/v0.2.0"))
            path == "/StrawWaffle/glorp/releases/tag/v0.2.0" -> respond("")
            path == "/StrawWaffle/glorp/releases/download/v0.2.0/glorp-0.2.0.apk.sha256" -> respond("$digest  glorp-0.2.0.apk\n")
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    private val installer = object : Installer {
        override suspend fun install(release: Release, progress: (Int) -> Unit): UpdateState = UpdateState.Idle
    }

    @Test
    fun findsTheReleaseWithoutTheApi() = runTest {
        val updates = Updates(client, MemoryChatPrefs(), "0.1.0", installer, this)
        updates.check(force = true)
        // The mock engine answers on its own threads: wait, in real time, for the check to end.
        val state = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) { updates.state.first { it !is UpdateState.Checking && it !is UpdateState.Idle } }
        }
        assertTrue(state is UpdateState.Available, "got $state")
        val release = state.release
        assertEquals("0.2.0", release.version)
        assertEquals("https://github.com/StrawWaffle/glorp/releases/download/v0.2.0/glorp-0.2.0.apk", release.apkUrl)
        assertEquals(digest, release.sha256)
    }
}
