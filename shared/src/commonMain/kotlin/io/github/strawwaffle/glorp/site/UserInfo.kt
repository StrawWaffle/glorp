package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the site's user menu shows about someone (`/api/chat/userinfo`). */
data class UserInfo(
    val nick: String,
    val features: List<String>,
    /** `2013-03-08T01:48:44+00:00`; null when not given. */
    val createdDate: String?,
    val age: String?,
    val gender: String?,
    val bio: String?,
    /** What they're watching on bigscreen, if anything. */
    val watching: Embed?,
)

sealed interface UserLookup {
    data class Found(val info: UserInfo) : UserLookup
    data object NotFound : UserLookup
    data object Failed : UserLookup
}

/** The site's own lookup, as chat-gui's user menu makes it: anonymous, by name. */
suspend fun lookUpUser(client: HttpClient, site: Site, nick: String): UserLookup = try {
    val response = client.get("${site.origin}/api/chat/userinfo") {
        parameter("username", nick)
        header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
    }
    when {
        response.status == HttpStatusCode.NotFound -> UserLookup.NotFound
        else -> parseUserInfo(response.bodyAsText())?.let(UserLookup::Found) ?: UserLookup.Failed
    }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    UserLookup.Failed
}

internal fun parseUserInfo(text: String): UserInfo? = runCatching {
    val body = chatJson.parseToJsonElement(text).jsonObject
    if ((body["success"] as? JsonPrimitive)?.contentOrNull != "true") return null
    val data = body["data"]!!.jsonObject
    fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val watching = (data["watching"] as? JsonObject)?.let { w ->
        val platform = (w["platform"] as? JsonPrimitive)?.contentOrNull
        val id = (w["id"] as? JsonPrimitive)?.contentOrNull
        if (platform != null && id != null) Embed.parse("#$platform/$id") else null
    }
    UserInfo(
        nick = data["nick"]!!.jsonPrimitive.content,
        features = (data["features"])?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        createdDate = text("createdDate"),
        age = text("age"),
        gender = text("gender"),
        bio = text("bio"),
        watching = watching,
    )
}.getOrNull()

/** The site's words for the age and gender values. */
fun ageLabel(value: String) = when (value) {
    "under-18" -> "Under 18"
    else -> value
}

fun genderLabel(value: String) = when (value) {
    "male" -> "Male"
    "female" -> "Female"
    "nonbinary" -> "Nonbinary"
    else -> value
}
