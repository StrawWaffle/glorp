package io.github.strawwaffle.glorp.chat

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Small things chat keeps on the device between launches: the ignore and
 * highlight lists and the last messages sent. Android keeps them in the
 * app's private preferences; nothing here is secret.
 */
interface ChatPrefs {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Keeps them only while the app runs. */
class MemoryChatPrefs : ChatPrefs {
    private val values = HashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private val stringList = ListSerializer(String.serializer())

internal fun ChatPrefs.getList(key: String): List<String> =
    get(key)?.let { runCatching { chatJson.decodeFromString(stringList, it) }.getOrNull() }.orEmpty()

internal fun ChatPrefs.putList(key: String, values: List<String>) =
    put(key, if (values.isEmpty()) null else chatJson.encodeToString(stringList, values))
