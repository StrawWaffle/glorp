package io.github.strawwaffle.glorp.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Chat's settings as destiny.gg's chat keeps them: one map of named values,
 * saved with the account (see [resolveSettings]) and on the device. Glorp
 * uses some of them; the rest are kept exactly as they came, so saving from
 * the app never loses what was set on the site.
 */
class ChatSettings(private val prefs: ChatPrefs) {
    /** Only what's been set; anything missing is its default. */
    var values: Map<String, JsonElement> by mutableStateOf(load())
        private set

    /** Called after a change made here (not after taking the account's copy). */
    var onChange: (() -> Unit)? = null

    operator fun get(key: String): JsonElement? = values[key] ?: SETTINGS_DEFAULTS[key]

    fun set(key: String, value: JsonElement) {
        if (values[key] == value) return
        values = values + (key to value)
        save()
        onChange?.invoke()
    }

    /** Takes [all] as the settings (the account's copy), without counting it as a change. */
    fun replace(all: Map<String, JsonElement>) {
        values = all
        save()
    }

    /** Everything, defaults filled in, as the site stores it. */
    fun full(): Map<String, JsonElement> = withDefaults(values)

    val showTime: Boolean get() = bool("showtime")
    val hideFlairIcons: Boolean get() = bool("hideflairicons")
    val highlightSelf: Boolean get() = bool("highlight")
    val taggedVisibility: Boolean get() = bool("taggedvisibility")
    val timestampFormat: String get() = (get("timestampformat") as? JsonPrimitive)?.contentOrNull ?: "HH:mm"

    fun setBool(key: String, on: Boolean) = set(key, JsonPrimitive(on))

    /** A list of names or words. */
    fun list(key: String): List<String> =
        (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    fun setList(key: String, items: List<String>) = set(key, JsonArray(items.map(::JsonPrimitive)))

    private fun bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false

    private fun save() = prefs.put(KEY, JsonObject(values).toString())

    private fun load(): Map<String, JsonElement> {
        val saved = prefs.get(KEY)
            ?.let { runCatching { chatJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            .orEmpty()
        // The ignore and highlight lists were kept on their own before.
        val moved = buildMap {
            putAll(saved)
            for ((old, key) in listOf("ignore" to "ignorenicks", "highlight" to "highlightnicks")) {
                val list = prefs.getList(old)
                if (list.isNotEmpty() && key !in saved) put(key, JsonArray(list.map(::JsonPrimitive)))
                if (prefs.get(old) != null) prefs.put(old, null)
            }
        }
        if (moved != saved) prefs.put(KEY, JsonObject(moved).toString())
        return moved
    }

    private companion object {
        const val KEY = "chat.settings"
    }
}

/** chat-gui's defaults (its `settingsdefault`). */
internal val SETTINGS_DEFAULTS: Map<String, JsonElement> = linkedMapOf(
    "schemaversion" to JsonPrimitive(2),
    "showtime" to JsonPrimitive(false),
    "showentireurl" to JsonPrimitive(false),
    "hideflairicons" to JsonPrimitive(false),
    "profilesettings" to JsonPrimitive(false),
    "timestampformat" to JsonPrimitive("HH:mm"),
    "maxlines" to JsonPrimitive(250),
    "notificationwhisper" to JsonPrimitive(false),
    "notificationhighlight" to JsonPrimitive(false),
    "highlight" to JsonPrimitive(true),
    "customhighlight" to JsonArray(emptyList()),
    "highlightnicks" to JsonArray(emptyList()),
    "taggednicks" to JsonArray(emptyList()),
    "taggednotes" to JsonArray(emptyList()),
    "showremoved" to JsonPrimitive(0),
    "showhispersinchat" to JsonPrimitive(false),
    "ignorenicks" to JsonArray(emptyList()),
    "favoriteemotes" to JsonArray(emptyList()),
    "focusmentioned" to JsonPrimitive(false),
    "notificationtimeout" to JsonPrimitive(true),
    "ignorementions" to JsonPrimitive(false),
    "autocompletehelper" to JsonPrimitive(true),
    "taggedvisibility" to JsonPrimitive(false),
    "hidensfw" to JsonPrimitive(false),
    "hidensfl" to JsonPrimitive(false),
    "fontscale" to JsonPrimitive("auto"),
    "censorbadwords" to JsonPrimitive(false),
    "disableanimations" to JsonPrimitive(false),
    "previewallimages" to JsonPrimitive(false),
    "collapsepolls" to JsonPrimitive(false),
)

// What follows is chat-gui's settingssync.js, so the app and the site agree
// on whose settings win. One addition: keys chat-gui doesn't know (yet) are
// kept, so a newer site's settings survive the app saving.

/** Bookkeeping rather than a choice: never merged, the account's copy is kept. */
private val BOOKKEEPING = setOf("schemaversion", "profilesettings")

/** Lists whose items are `[key, value]` pairs. */
private val PAIR_LISTS = setOf("taggednicks", "taggednotes")

private fun JsonElement?.has() = this != null && this != JsonNull

private fun isCustomized(key: String, value: JsonElement?) = value.has() && value != SETTINGS_DEFAULTS[key]

private fun unionList(key: String, server: JsonArray, local: JsonArray): JsonArray {
    if (key in PAIR_LISTS) {
        // Keyed by each pair's first item; the account's copy wins a clash.
        val byKey = LinkedHashMap<JsonElement, JsonElement>()
        for (item in local + server) byKey[(item as? JsonArray)?.firstOrNull() ?: item] = item
        return JsonArray(byKey.values.toList())
    }
    return JsonArray((server + local).distinct())
}

/** A device's first sync: only values that differ from the defaults count; lists are combined. */
internal fun mergeSettings(
    server: Map<String, JsonElement>,
    local: Map<String, JsonElement>,
    legacy: Boolean = false,
): Map<String, JsonElement> {
    val merged = LinkedHashMap<String, JsonElement>()
    for (key in SETTINGS_DEFAULTS.keys) {
        val serverValue = server[key]
        val localValue = local[key]
        if (key in BOOKKEEPING) {
            if (serverValue.has()) merged[key] = serverValue!!
            else if (key == "schemaversion" && isCustomized(key, localValue)) merged[key] = localValue!!
            continue
        }
        val isList = SETTINGS_DEFAULTS[key] is JsonArray
        val serverSet = isCustomized(key, serverValue)
        val localSet = isCustomized(key, localValue)
        when {
            isList && serverSet && localSet && serverValue is JsonArray && localValue is JsonArray ->
                merged[key] = unionList(key, serverValue, localValue)
            serverSet || (legacy && !isList && serverValue.has()) -> merged[key] = serverValue!!
            localSet -> merged[key] = localValue!!
            serverValue.has() -> merged[key] = serverValue!!
        }
    }
    return merged
}

internal fun sameSettings(a: Map<String, JsonElement>, b: Map<String, JsonElement>): Boolean =
    SETTINGS_DEFAULTS.keys.filter { it !in BOOKKEEPING }.all { key ->
        val fallback = SETTINGS_DEFAULTS[key]
        (a[key].takeIf { it.has() } ?: fallback) == (b[key].takeIf { it.has() } ?: fallback)
    }

/** Every setting: from [settings] where it has a value, the defaults otherwise, and anything else it holds. */
internal fun withDefaults(settings: Map<String, JsonElement>): Map<String, JsonElement> {
    val out = LinkedHashMap<String, JsonElement>()
    for ((key, fallback) in SETTINGS_DEFAULTS) out[key] = settings[key].takeIf { it.has() } ?: fallback
    for ((key, value) in settings) if (key !in out) out[key] = value
    return out
}

internal data class Resolved(val settings: Map<String, JsonElement>, val upload: Boolean)

/**
 * What to do with the account's copy just loaded:
 * - The device last synced for another account: take the account's copy.
 * - This device has changes for this user the account's copy lacks: keep and upload them.
 * - This device has synced for this user: the account's copy is the truth.
 * - Otherwise (first sync, or an empty copy): merge, and upload if that changed it.
 */
internal fun resolveSettings(
    server: Map<String, JsonElement>,
    local: Map<String, JsonElement>,
    userId: String,
    syncedFor: String?,
    pending: Boolean,
): Resolved {
    if (syncedFor != null && syncedFor != userId) return Resolved(withDefaults(server), upload = false)
    if (syncedFor == userId && pending) {
        val settings = LinkedHashMap(local)
        server["profilesettings"]?.takeIf { it.has() }?.let { settings["profilesettings"] = it }
        return Resolved(settings, upload = true)
    }
    if (syncedFor == userId && server.isNotEmpty()) return Resolved(server, upload = false)
    val legacy = (server["profilesettings"] as? JsonPrimitive)?.booleanOrNull == true
    // Keys chat-gui doesn't know yet are kept as they are.
    val merged = LinkedHashMap(server).apply { putAll(mergeSettings(server, local, legacy)) }
    return Resolved(merged, upload = !sameSettings(merged, server))
}

/** The site's form: a list of `[key, value]` pairs. */
internal fun settingsFromSite(text: String): Map<String, JsonElement>? = runCatching {
    chatJson.parseToJsonElement(text).jsonArray.associate { pair ->
        val (key, value) = pair.jsonArray
        (key as JsonPrimitive).content to value
    }
}.getOrNull()

internal fun settingsToSite(settings: Map<String, JsonElement>): String =
    JsonArray(settings.map { (key, value) -> JsonArray(listOf(JsonPrimitive(key), value)) }).toString()

/**
 * A time as chat-gui's `timestampformat` (moment.js) writes it: `HH` `H`
 * `hh` `h` `mm` `m` `ss` `s` `A` `a`, text in `[brackets]`, anything else
 * as it is.
 */
fun formatTime(hour: Int, minute: Int, second: Int, format: String): String {
    val out = StringBuilder()
    var i = 0
    fun two(n: Int) = n.toString().padStart(2, '0')
    val h12 = (hour % 12).let { if (it == 0) 12 else it }
    while (i < format.length) {
        val rest = format.substring(i)
        val token = listOf("HH", "hh", "mm", "ss", "H", "h", "m", "s", "A", "a").firstOrNull { rest.startsWith(it) }
        when {
            rest.startsWith("[") && rest.indexOf(']') > 0 -> {
                val end = rest.indexOf(']')
                out.append(rest, 1, end)
                i += end + 1
                continue
            }
            token == null -> { out.append(format[i]); i++; continue }
        }
        out.append(
            when (token) {
                "HH" -> two(hour)
                "H" -> hour.toString()
                "hh" -> two(h12)
                "h" -> h12.toString()
                "mm" -> two(minute)
                "m" -> minute.toString()
                "ss" -> two(second)
                "s" -> second.toString()
                "A" -> if (hour < 12) "AM" else "PM"
                else -> if (hour < 12) "am" else "pm"
            },
        )
        i += token!!.length
    }
    return out.toString()
}
