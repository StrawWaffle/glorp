package io.github.strawwaffle.glorp.notify

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.ChatSettings
import io.github.strawwaffle.glorp.chat.MemoryChatPrefs
import io.github.strawwaffle.glorp.community.RustleLink
import io.github.strawwaffle.glorp.community.rustleLinkOf
import io.github.strawwaffle.glorp.site.Site
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var live = false
    private var mentions = "[]"
    private var conversations = "[]"
    private val requests = mutableListOf<String>()
    private var served = "[]"
    /** What polecat.me answers; null: it's down. */
    private var polecat: String? = null

    private val client = HttpClient(MockEngine { request ->
        requests += request.url.encodedPath
        when (request.url.encodedPath) {
            "/api/info/stream" -> respond(
                """{"success":true,"data":{"streams":{"twitch":null,"youtube":{"live":$live,"status_text":"chillin","started_at":"2026-11-23T20:00:00+0000","platform":"youtube"}}}}""",
                headers = json,
            )
            "/api/chat/mentions" -> respond("""{"success":true,"data":$mentions}""", headers = json)
            "/m/mentions" -> respond("""{"mentions":$served}""", headers = json)
            "/api/mentions/Me" -> polecat?.let { respond(it, headers = json) } ?: respond("", io.ktor.http.HttpStatusCode.BadGateway)
            "/api/messages/conversations" -> respond("""{"data":{"results":$conversations}}""", headers = json)
            else -> respond("", io.ktor.http.HttpStatusCode.NotFound)
        }
    })

    private val prefs = MemoryChatPrefs()
    private val settings = ChatSettings(prefs)
    private val options = NotifyOptions(prefs, settings)
    private val site = Site.guess("https://www.destiny.gg")
    private val cookies = Session.cookies("s", "r")

    private fun check(signedIn: Boolean = true) = AlertCheck(client, site, prefs, options, if (signedIn) cookies else null, "Me")

    @Test
    fun liveOnceWhenItStartsAndNotOnTheFirstLook() = runTest {
        options.setLive(true)
        live = true
        // The first look only notes how things are.
        assertEquals(emptyList(), check().run())
        live = false
        assertEquals(emptyList(), check().run())
        live = true
        val alerts = check().run()
        assertEquals(1, alerts.size)
        assertEquals("Destiny is live on YouTube", alerts[0].title)
        assertEquals(AlertCheck.OPEN_LIVE, alerts[0].open)
        // Still the same stream: nothing more.
        assertEquals(emptyList(), check().run())
    }

    @Test
    fun newMentionsOpenTheChatAroundThem() = runTest {
        settings.set("notificationhighlight", JsonPrimitive(true))
        mentions = """[{"date":1795400000,"nick":"Friend","text":"hey Me"}]"""
        assertEquals(emptyList(), check().run())
        mentions = """[{"date":1795400600,"nick":"Friend","text":"Me look"},{"date":1795400000,"nick":"Friend","text":"hey Me"},{"date":1795400700,"nick":"Me","text":"me, Me"}]"""
        val alerts = check().run()
        assertEquals(listOf("Friend mentioned you"), alerts.map { it.title })
        val around = rustleLinkOf(alerts[0].open) as RustleLink.Around
        assertEquals("Friend", around.message.username)
        assertEquals(1795400600_000, around.message.at)
    }

    @Test
    fun unreadWhispersFromOthers() = runTest {
        settings.set("notificationwhisper", JsonPrimitive(true))
        conversations = """[{"username":"Old","unread":1,"lastMessage":"x","timestamp":"2026-11-23T10:00:00+0000"}]"""
        assertEquals(emptyList(), check().run())
        conversations = """[{"username":"Pal","unread":2,"lastMessage":"you there?","timestamp":"2026-11-23T11:00:00+0000"},""" +
            """{"username":"Sent","unread":0,"lastMessage":"mine","timestamp":"2026-11-23T11:05:00+0000","lastMessageFromMe":true}]"""
        val alerts = check().run()
        assertEquals(listOf("Whisper from Pal"), alerts.map { it.title })
        assertEquals(AlertCheck.OPEN_WHISPER + "Pal", alerts[0].open)
    }

    @Test
    fun theServiceFindsMentionsWithoutTheSitesLogin() = runTest {
        settings.set("notificationhighlight", JsonPrimitive(true))
        val check = { AlertCheck(client, site, prefs, options, null, "Me", server = "https://glorp.example/m/") }
        // Said after the first look, which starts from now.
        val first = kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000
        val second = first + 60_123
        served = """[{"nick":"Friend","text":"hey Me","at":$first}]"""
        assertEquals(emptyList(), check().run())
        served = """[{"nick":"Friend","text":"hey Me","at":$first},{"nick":"Pal","text":"Me?","at":$second}]"""
        val alerts = check().run()
        assertEquals(listOf("Pal mentioned you"), alerts.map { it.title })
        // The exact time: the link goes straight to the message.
        assertEquals(second, (rustleLinkOf(alerts[0].open) as RustleLink.Around).message.at)
        // polecat.me was down each time, so the service answered.
        assertTrue(requests.all { it == "/m/mentions" || it == "/api/mentions/Me" })
    }

    @Test
    fun polecatComesFirst() = runTest {
        settings.set("notificationhighlight", JsonPrimitive(true))
        val check = { AlertCheck(client, site, prefs, options, null, "Me", server = "https://glorp.example/m/") }
        val first = kotlin.time.Clock.System.now().toEpochMilliseconds() + 60_000
        val second = first + 61_000
        polecat = """[{"id":1,"date":$first,"nick":"Friend","flairs":"","text":"hey Me"}]"""
        assertEquals(emptyList(), check().run())
        polecat = """[{"id":2,"date":$second,"nick":"Pal","flairs":"","text":"Me?"},{"id":1,"date":$first,"nick":"Friend","flairs":"","text":"hey Me"}]"""
        val alerts = check().run()
        assertEquals(listOf("Pal mentioned you"), alerts.map { it.title })
        assertEquals(second, (rustleLinkOf(alerts[0].open) as RustleLink.Around).message.at)
        // The service wasn't needed.
        assertTrue("/m/mentions" !in requests)
    }

    @Test
    fun withoutTheSitesLoginNoWhispers() = runTest {
        options.setLive(true)
        settings.set("notificationhighlight", JsonPrimitive(true))
        settings.set("notificationwhisper", JsonPrimitive(true))
        check(signedIn = false).run()
        // Mentions come from polecat.me's logs, which need no login; whispers need the site's.
        assertEquals(listOf("/api/info/stream", "/api/mentions/Me"), requests)
    }

    @Test
    fun nothingWantedAsksNothing() = runTest {
        check().run()
        assertTrue(requests.isEmpty())
    }
}
