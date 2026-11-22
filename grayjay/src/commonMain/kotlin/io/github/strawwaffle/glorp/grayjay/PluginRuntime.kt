package io.github.strawwaffle.glorp.grayjay

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import kotlin.random.Random

private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

/** The plugin threw: the video is unavailable, YouTube changed something, and so on. */
class PluginException(message: String) : Exception(message)

/**
 * One loaded Grayjay source plugin, the way Grayjay's app runs one: its
 * packages first (here [Prelude.HOST]), then Grayjay's polyfil.js and
 * source.js, then the plugin, then `source.enable(config, settings, null)`.
 *
 * Plugin code is synchronous and blocks its thread on network calls, as it
 * does in Grayjay; run it on a background dispatcher. Close it when done: the
 * engine holds the whole plugin in memory.
 */
class PluginRuntime private constructor(
    private val js: QuickJs,
    private val http: HttpClient,
) : AutoCloseable {

    /**
     * `source.getContentDetails(url)`, reduced to what a player needs: a
     * JSON object with `name`, `isLive`, `live` (an HLS address, or null),
     * and `sources` and `audio`, the video descriptor's sources. Each source
     * has its type, name and url, what the plugin says about its format
     * (width, height, container, codec, bitrate, duration in seconds, itag,
     * and the byte ranges of its init and index), whether it's the original
     * audio, and `headers`: what the source's request modifier adds to a
     * request for it.
     */
    suspend fun contentDetails(url: String): String {
        // The engine runs the promise to completion but hands back the promise
        // itself, so the answer goes through a global.
        js.evaluate<Any?>(
        """
        globalThis.__details = null;
        (async () => {
            const d = await source.getContentDetails(${JsonPrimitive(url)});
            const headersOf = (s) => {
                try {
                    const m = s.getRequestModifier && s.getRequestModifier();
                    return (m && m.modifyRequest && s.url) ? (m.modifyRequest(s.url, {}).headers || {}) : {};
                } catch (e) { return {}; }
            };
            const num = (n) => (typeof n === "number" && isFinite(n)) ? n : null;
            const pick = (s) => s ? {
                type: s.plugin_type || null, name: s.name || null, url: s.url || null,
                width: num(s.width), height: num(s.height), container: s.container || null, codec: s.codec || null,
                bitrate: num(s.bitrate), duration: num(s.duration), itag: num(s.itagId),
                initStart: num(s.initStart), initEnd: num(s.initEnd), indexStart: num(s.indexStart), indexEnd: num(s.indexEnd),
                original: s.original === true, headers: headersOf(s),
            } : null;
            const v = d && d.video;
            return JSON.stringify({
                name: (d && d.name) || null,
                isLive: !!(d && d.isLive),
                live: pick(d && (d.live || d.hls)),
                sources: v ? (v.videoSources || []).map(pick) : [],
                audio: v ? (v.audioSources || []).map(pick) : [],
            });
        })().then((r) => { globalThis.__details = { ok: r }; }, (e) => { globalThis.__details = { error: String(e && e.message || e) }; });
        """.trimIndent(),
        "details.js",
        )
        val answer = js.evaluate<String?>("__details == null ? null : JSON.stringify(__details)", "result.js")
            ?: error("The plugin didn't answer")
        val o = json.parseToJsonElement(answer).jsonObject
        (o["error"] as? JsonPrimitive)?.contentOrNull?.let { throw PluginException(it) }
        return (o["ok"] as JsonPrimitive).content
    }

    override fun close() {
        js.close()
        http.close()
    }

    companion object {
        /**
         * Loads a plugin. [config] is its config JSON (YoutubeConfig.json),
         * [script] its script, already checked against the config's
         * signature. [settings] override the config's defaults.
         */
        suspend fun load(
            config: String,
            script: String,
            http: HttpClient,
            dispatcher: CoroutineDispatcher,
            settings: Map<String, String> = emptyMap(),
            log: (String) -> Unit = {},
            trace: ((String) -> Unit)? = null,
        ): PluginRuntime {
            val js = QuickJs.create(dispatcher)
            try {
                // The plugin runs YouTube's player code and Google's BotGuard;
                // both need room.
                js.memoryLimit = 512L * 1024 * 1024
                js.maxStackSize = 8L * 1024 * 1024
                bindHost(js, http, log, trace)
                js.evaluate<Any?>(Prelude.HOST, "host.js")
                js.evaluate<Any?>(Prelude.POLYFIL, "polyfil.js")
                js.evaluate<Any?>(Prelude.SOURCE, "source.js")
                js.evaluate<Any?>(script, "plugin.js")
                val configJson = json.parseToJsonElement(config).jsonObject
                val settingsJson = JsonObject(defaultSettings(configJson) + settings.mapValues { JsonPrimitive(it.value) })
                js.evaluate<Any?>(
                    "plugin.config = $configJson; plugin.settings = parseSettings($settingsJson);" +
                        "source.enable(plugin.config, parseSettings($settingsJson), null);",
                    "enable.js",
                )
                return PluginRuntime(js, http)
            } catch (e: Throwable) {
                js.close()
                throw e
            }
        }

        /** `variable -> default` from the config's `settings`, as strings, which `parseSettings` reads. */
        private fun defaultSettings(config: JsonObject): Map<String, JsonElement> =
            (config["settings"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                val name = (o["variable"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val value = o["default"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
                name to JsonPrimitive(value)
            }.toMap()

        private fun bindHost(js: QuickJs, http: HttpClient, log: (String) -> Unit, trace: ((String) -> Unit)?) {
            js.define("__host") {
                function("log", FunctionBinding<Any?> { args -> log(args.firstOrNull()?.toString() ?: ""); null })
                function("sleep", FunctionBinding<Any?> { args -> runBlocking { delay((args[0] as Number).toLong()) }; null })
                function("uuid", FunctionBinding<Any?> { _ -> randomUuid() })
                function("hashString", FunctionBinding<Any?> { args ->
                    val text = (args[1] as String).encodeUtf8()
                    (if (args[0] == "md5") text.md5() else text.sha256()).hex()
                })
                function("hash", FunctionBinding<Any?> { args ->
                    val bytes = (args[1] as String).decodeBase64() ?: okio.ByteString.EMPTY
                    (if (args[0] == "md5") bytes.md5() else bytes.sha256()).base64()
                })
                function("request", FunctionBinding<Any?> { args ->
                    val request = Request(
                        method = args[0] as String,
                        url = args[1] as String,
                        headers = json.parseToJsonElement(args[2] as String).jsonObject,
                        body = args[3] as String?,
                        bodyIsBytes = args[4] as Boolean,
                    )
                    val bytes = args[5] as Boolean
                    runBlocking { perform(http, request, bytes, trace) }.toString()
                })
                function("batch", FunctionBinding<Any?> { args ->
                    val requests = json.parseToJsonElement(args[0] as String).jsonArray
                    runBlocking {
                        coroutineScope {
                            requests.map { element ->
                                async {
                                    if (element is JsonNull) JsonNull
                                    else perform(http, Request.from(element.jsonObject), bytes = false, trace = trace)
                                }
                            }.awaitAll()
                        }
                    }.let(::JsonArray).toString()
                })
            }
            js.asyncFunction("__delay") { args -> delay((args[0] as Number).toLong()) }
        }

        private class Request(
            val method: String,
            val url: String,
            val headers: JsonObject,
            val body: String?,
            val bodyIsBytes: Boolean,
        ) {
            companion object {
                fun from(o: JsonObject) = Request(
                    method = o["method"]?.jsonPrimitive?.contentOrNull ?: "GET",
                    url = o["url"]?.jsonPrimitive?.contentOrNull ?: "",
                    headers = o["headers"] as? JsonObject ?: JsonObject(emptyMap()),
                    body = (o["body"] as? JsonPrimitive)?.contentOrNull,
                    bodyIsBytes = (o["bodyIsBytes"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        }

        /** The response shape Grayjay's Http package returns: url, code, body, headers (lowercase name -> values), isOk. */
        private suspend fun perform(http: HttpClient, r: Request, bytes: Boolean, trace: ((String) -> Unit)? = null): JsonObject = try {
            var contentType: ContentType? = null
            val response: HttpResponse = http.request(r.url) {
                method = HttpMethod.parse(r.method.uppercase())
                var hasAgent = false
                for ((name, value) in r.headers) {
                    val v = (value as? JsonPrimitive)?.contentOrNull ?: continue
                    when {
                        name.equals("content-type", ignoreCase = true) -> contentType = runCatching { ContentType.parse(v) }.getOrNull()
                        else -> header(name, v)
                    }
                    if (name.equals("user-agent", ignoreCase = true)) hasAgent = true
                }
                // Grayjay's client sends this when a plugin doesn't set one.
                if (!hasAgent) header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0")
                if (r.body != null) {
                    val type = contentType ?: ContentType.Application.OctetStream
                    if (r.bodyIsBytes) setBody(ByteArrayContent(r.body.decodeBase64()?.toByteArray() ?: ByteArray(0), type))
                    else setBody(TextContent(r.body, type))
                }
            }
            val body = if (bytes) okio.ByteString.of(*response.bodyAsBytes()).base64() else response.bodyAsText()
            trace?.invoke(
                "${r.method} ${r.url.take(140)} -> ${response.status.value}\n  sent ${r.headers.keys}" +
                    (r.body?.let { "\n  body ${it.take(600)}" } ?: "") + "\n  got ${body.take(500)}",
            )
            JsonObject(
                mapOf(
                    "url" to JsonPrimitive(response.call.request.url.toString()),
                    "code" to JsonPrimitive(response.status.value),
                    "body" to JsonPrimitive(body),
                    "headers" to JsonObject(
                        response.headers.entries().associate { (k, v) -> k.lowercase() to JsonArray(v.map(::JsonPrimitive)) },
                    ),
                    "isOk" to JsonPrimitive(response.status.value in 200..299),
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            JsonObject(mapOf("url" to JsonPrimitive(""), "code" to JsonPrimitive(0), "body" to JsonNull, "headers" to JsonObject(emptyMap()), "isOk" to JsonPrimitive(false), "error" to JsonPrimitive(e.toString())))
        }

        private fun randomUuid(): String {
            val b = Random.nextBytes(16)
            b[6] = (b[6].toInt() and 0x0f or 0x40).toByte()
            b[8] = (b[8].toInt() and 0x3f or 0x80).toByte()
            val hex = okio.ByteString.of(*b).hex()
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}
