package io.github.strawwaffle.glorp.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** SHA-256, AES-256-GCM and random bytes, from the platform. Null where it has none (sync is then off). */
expect object SyncCrypto {
    val available: Boolean
    fun sha256(data: ByteArray): ByteArray
    fun random(size: Int): ByteArray
    /** IV (12 bytes) followed by the ciphertext and tag. */
    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray?
}

/**
 * Keeps Glorp's own settings (those destiny.gg has no place for) the same in
 * every Glorp app and in the Glorp userscript, through the sync service
 * (sync/README.md). A sync code is 16 random bytes; the server sees only an ID
 * and ciphertext made from it.
 *
 * Each setting carries when it was last changed. What changed here since the
 * last sync is found by comparing with what that sync left ([SNAPSHOT]), so
 * nothing else has to report its changes; the newer of each setting wins.
 */
class GlorpSync(
    private val prefs: ChatPrefs,
    private val options: LocalOptions,
    private val theme: () -> String,
    private val setTheme: (String) -> Unit,
    private val client: HttpClient,
    private val server: String = DEFAULT_SERVER,
) {
    var code by mutableStateOf(prefs.get(CODE))
        private set
    /** What the last sync did: null before one, else a sentence for the settings screen. */
    var status by mutableStateOf<String?>(null)
        private set
    private var lastRun = 0L

    val available: Boolean get() = SyncCrypto.available

    /** The code as the userscript and other phones take it. */
    val codeText: String? get() = code?.let { "glorp-sync:1:$it" }

    fun start() {
        val bytes = SyncCrypto.random(16)
        keepCode(bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
    }

    /** Takes a code from the userscript or another phone; false if [text] isn't one. */
    fun join(text: String): Boolean {
        val hex = Regex("""^glorp-sync:1:([0-9a-fA-F]{32})$""").find(text.trim())?.groupValues?.get(1)?.lowercase() ?: return false
        // Joining: what's there wins over this phone's settings, which were never synced.
        prefs.put(SNAPSHOT, null)
        keepCode(hex)
        return true
    }

    fun stop() {
        keepCode(null)
        prefs.put(SNAPSHOT, null)
        status = null
    }

    private fun keepCode(hex: String?) {
        code = hex
        prefs.put(CODE, hex)
    }

    /** Whether to sync now: something changed here, or it's been a while. */
    fun due(now: Long = Clock.System.now().toEpochMilliseconds()): Boolean =
        code != null && (now - lastRun > EVERY_MS || current() != snapshot().mapValues { it.value.first })

    /** This phone's synced settings as they are now. */
    private fun current(): Map<String, JsonElement> = mapOf(
        "theme" to JsonPrimitive(theme()),
        "nameIcons" to JsonObject(options.nameIcons.mapValues { JsonPrimitive(it.value) }),
        "otherStreams" to JsonPrimitive(options.otherStreams.name),
        "stickyMentions" to JsonPrimitive(options.stickyMentions),
        "linkTitles" to JsonPrimitive(options.linkTitles),
        "hiddenPhrases" to JsonArray(options.hiddenPhrases.map(::JsonPrimitive)),
        "hiddenFlairs" to JsonArray(options.hiddenFlairs.sorted().map(::JsonPrimitive)),
        "alertWords" to JsonArray(options.alertWords.map(::JsonPrimitive)),
    )

    /** Takes a synced value for [key], where this app has the setting. */
    private fun take(key: String, value: JsonElement) {
        runCatching {
            when (key) {
                "theme" -> value.jsonPrimitive.contentOrNull?.takeIf { it.isNotEmpty() }?.let(setTheme)
                "nameIcons" -> options.replaceIcons(value.jsonObject.mapValues { it.value.jsonPrimitive.content })
                "otherStreams" -> OtherStreams.entries.firstOrNull { it.name == value.jsonPrimitive.content }?.let(options::treatOtherStreams)
                "stickyMentions" -> value.jsonPrimitive.booleanOrNull?.let(options::keepMentions)
                "linkTitles" -> value.jsonPrimitive.booleanOrNull?.let(options::showLinkTitles)
                "hiddenPhrases" -> options.hidePhrases((value as JsonArray).map { it.jsonPrimitive.content })
                "hiddenFlairs" -> options.replaceHiddenFlairs((value as JsonArray).map { it.jsonPrimitive.content }.toSet())
                "alertWords" -> options.alertOn((value as JsonArray).map { it.jsonPrimitive.content })
            }
        }
    }

    /** What the last sync left: value and time, by setting. */
    private fun snapshot(): Map<String, Pair<JsonElement, Long>> = runCatching {
        chatJson.parseToJsonElement(prefs.get(SNAPSHOT) ?: return emptyMap()).jsonObject.mapValues { (_, e) ->
            val o = e.jsonObject
            o["v"]!! to (o["t"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
    }.getOrDefault(emptyMap())

    private fun encode(settings: Map<String, Pair<JsonElement, Long>>): JsonObject = buildJsonObject {
        for ((k, vt) in settings) put(k, buildJsonObject { put("v", vt.first); put("t", vt.second) })
    }

    /** Fetches, keeps the newer of each setting, applies them here and sends the result back. */
    suspend fun sync() {
        val hex = code ?: return
        if (!SyncCrypto.available) return
        val now = Clock.System.now().toEpochMilliseconds()
        lastRun = now
        status = try {
            val secret = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val id = SyncCrypto.sha256("glorp-sync-id:".encodeToByteArray() + secret)
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(32)
            val key = SyncCrypto.sha256("glorp-sync-key:".encodeToByteArray() + secret)
            val url = "$server/api/sync/$id"

            // Mine: as last synced, with what changed since stamped now.
            val before = snapshot()
            val mine = current().mapValues { (k, v) ->
                val was = before[k]
                if (was != null && was.first == v) was else v to (if (before.isEmpty()) 0L else now)
            }
            // Theirs, if there is anything yet.
            val response = client.get(url) { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
            val theirs: Map<String, Pair<JsonElement, Long>> = when {
                response.status == HttpStatusCode.NotFound -> emptyMap()
                !response.status.isSuccess() -> error("the sync service answered ${response.status.value}")
                else -> {
                    val data = chatJson.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonPrimitive.content
                    val plain = SyncCrypto.decrypt(key, Base64.decode(data)) ?: error("the settings there don't open with this code")
                    chatJson.parseToJsonElement(plain.decodeToString()).jsonObject["s"]?.jsonObject.orEmpty().mapValues { (_, e) ->
                        val o = e.jsonObject
                        o["v"]!! to (o["t"]?.jsonPrimitive?.longOrNull ?: 0L)
                    }
                }
            }
            // Each setting the newer; settings this app doesn't have are kept as they came.
            val merged = HashMap(theirs)
            for ((k, vt) in mine) {
                val other = theirs[k]
                if (other == null || vt.second > other.second) merged[k] = vt
            }
            for ((k, vt) in merged) if (current()[k] != null && current()[k] != vt.first) take(k, vt.first)
            prefs.put(SNAPSHOT, encode(merged).toString())

            val doc = buildJsonObject { put("v", 1); put("s", encode(merged)) }.toString().encodeToByteArray()
            val body = buildJsonObject { put("data", Base64.encode(SyncCrypto.encrypt(key, doc))) }.toString()
            val sent = client.put(url) {
                header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!sent.status.isSuccess()) error("the sync service answered ${sent.status.value}")
            "Synced"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "Couldn't sync: ${e.message ?: "no connection"}"
        }
    }

    companion object {
        const val DEFAULT_SERVER = "https://glorp-sync.netlify.app"
        private const val CODE = "sync.code"
        private const val SNAPSHOT = "sync.snapshot"
        /** A sync at least this often while Glorp is open, for changes made elsewhere. */
        const val EVERY_MS = 5 * 60_000L
    }
}
