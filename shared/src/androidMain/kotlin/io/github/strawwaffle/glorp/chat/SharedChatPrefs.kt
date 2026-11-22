package io.github.strawwaffle.glorp.chat

import android.content.Context

/** [ChatPrefs] in the app's private shared preferences. */
class SharedChatPrefs(context: Context) : ChatPrefs {
    private val prefs = context.getSharedPreferences("chat", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
