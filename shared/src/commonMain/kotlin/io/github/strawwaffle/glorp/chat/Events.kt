package io.github.strawwaffle.glorp.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * The chat events chat-gui shows besides messages, read as it reads them:
 * paid events (subscriptions, gifts, donations), deaths, posts from X, the
 * Vestaboard auction, spotlights, and moderation notices. The wording is
 * chat-gui's.
 */

@Serializable
internal data class WireEventUser(val nick: String = "", val features: List<String> = emptyList())

@Serializable
internal data class WirePaid(
    val user: WireEventUser? = null,
    val recipient: WireEventUser? = null,
    val tier: Int = 0,
    val tierLabel: String? = null,
    val amount: Long = 0,
    val streak: Int = 0,
    val quantity: Int = 0,
    val fromMassGift: Boolean = false,
    val data: String? = null,
    val timestamp: Long? = null,
    val uuid: String? = null,
)

@Serializable
internal data class WireDeath(val nick: String = "", val data: String = "", val features: List<String> = emptyList(), val timestamp: Long? = null)

@Serializable
internal data class WireXPost(val handle: String = "", val text: String = "", val url: String? = null, val timestamp: Long? = null, val uuid: String? = null)

@Serializable
internal data class WireVestaboard(
    val submitter: String? = null,
    val artist: String? = null,
    val total: Long = 0,
    val isNewDesign: Boolean = false,
    val timestamp: Long? = null,
    val uuid: String? = null,
)

@Serializable
internal data class WireModeration(val nick: String = "", val data: String = "", val timestamp: Long? = null)

@Serializable
internal data class WireSpotlight(
    val key: String = "",
    val nick: String? = null,
    val user: WireEventUser? = null,
    val data: String = "",
    val messageTimestamp: Long = 0,
    val spotlightedBy: String? = null,
)

/** A spotlighted message: who wrote [text] at [messageAt], and who spotlighted it. */
data class Spot(val key: String, val nick: String, val text: String, val messageAt: Long, val by: String?)

/** A dollar amount from cents, as chat-gui writes it: $5.00, $1,234.50. */
internal fun usd(cents: Long): String {
    val dollars = cents / 100
    val grouped = dollars.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$$grouped.${(cents % 100).toString().padStart(2, '0')}"
}

private fun tierLabel(tier: Int, label: String?) = label?.takeIf { it.isNotBlank() } ?: "Tier $tier"

/** One event frame as a chat line, or null when it isn't one or can't be read. */
internal fun parseEventLine(event: String, payload: String, now: () -> Long): ChatLine? = when (event) {
    "SUBSCRIPTION" -> chatJson.decodeFromString<WirePaid>(payload).let { p ->
        val user = p.user ?: return null
        val streak = if (p.streak > 1) " · ${p.streak} month streak" else ""
        ChatLine.event(
            EventLine(EventLine.Kind.Subscription, "is now a ${tierLabel(p.tier, p.tierLabel)} subscriber$streak"),
            user.nick, user.features, p.data.orEmpty(), p.timestamp ?: now(), p.uuid,
        )
    }
    "GIFTSUB" -> chatJson.decodeFromString<WirePaid>(payload).let { p ->
        val user = p.user ?: return null
        val to = p.recipient?.nick ?: return null
        ChatLine.event(
            EventLine(EventLine.Kind.Gift, "gifted $to a ${tierLabel(p.tier, p.tierLabel)} subscription"),
            user.nick, user.features, p.data.orEmpty(), p.timestamp ?: now(), p.uuid,
        )
    }
    "MASSGIFT" -> chatJson.decodeFromString<WirePaid>(payload).let { p ->
        val user = p.user ?: return null
        val n = p.quantity.coerceAtLeast(1)
        ChatLine.event(
            EventLine(EventLine.Kind.MassGift, "gifted $n ${tierLabel(p.tier, p.tierLabel)} ${if (n > 1) "subs" else "sub"} to the community"),
            user.nick, user.features, p.data.orEmpty(), p.timestamp ?: now(), p.uuid,
        )
    }
    "DONATION" -> chatJson.decodeFromString<WirePaid>(payload).let { p ->
        val user = p.user ?: return null
        ChatLine.event(
            EventLine(EventLine.Kind.Donation, "donated ${usd(p.amount)}"),
            user.nick, user.features, p.data.orEmpty(), p.timestamp ?: now(), p.uuid,
        )
    }
    // `/die`: the message names who died, so no name goes before it.
    "DEATH" -> chatJson.decodeFromString<WireDeath>(payload).takeIf { it.data.isNotBlank() }?.let { d ->
        // Who it happened to: the text ("got crushed by kitty litter") leaves the name out, as chat-gui shows it before.
        val nick = d.nick.takeIf { it.isNotBlank() }
        // Some deaths name them in the text too ("Pal died of dysentery"): not twice.
        val text = if (nick != null && d.data.startsWith("$nick ", ignoreCase = true)) d.data.substring(nick.length + 1) else d.data
        ChatLine.event(EventLine(EventLine.Kind.Death, text), nick, d.features, "", d.timestamp ?: now(), null)
    }
    "XPOST" -> chatJson.decodeFromString<WireXPost>(payload).takeIf { it.handle.isNotEmpty() }?.let { x ->
        ChatLine.event(
            EventLine(EventLine.Kind.XPost, "@${x.handle} posted on X", x.url?.takeIf { it.startsWith("https://") }),
            null, emptyList(), x.text, x.timestamp ?: now(), x.uuid,
        )
    }
    "VESTABOARD_HOURLY", "VESTABOARD_LEAD", "VESTABOARD_RESET" -> chatJson.decodeFromString<WireVestaboard>(payload).let { v ->
        val amount = usd(v.total)
        val (nick, header, prompt) = when {
            event == "VESTABOARD_HOURLY" && v.submitter == null ->
                Triple(null, "No designs on the Vestaboard yet", "Be the first. Submit a design to claim the board.")
            event == "VESTABOARD_LEAD" && !v.isNewDesign ->
                Triple(v.submitter, "funded ${v.artist ?: "a"}'s design into the lead · $amount", "Think you can top it? Fund a challenger or submit your own.")
            event == "VESTABOARD_LEAD" ->
                Triple(v.submitter, "took the lead with a new design · $amount", "Think you can top it? Fund a challenger or submit your own.")
            event == "VESTABOARD_HOURLY" ->
                Triple(v.submitter, "is leading the Vestaboard · $amount", "The auction's live. Fund a design to take the lead.")
            else ->
                Triple(v.submitter, "won the Vestaboard auction · $amount", "A new auction begins now! Submit a design to claim the board.")
        }
        ChatLine.event(EventLine(EventLine.Kind.Vestaboard, header), nick, emptyList(), prompt, v.timestamp ?: now(), v.uuid)
    }
    else -> null
}

/**
 * A moderation frame: who it's about (to censor, for a mute or ban) and the
 * notice chat-gui writes.
 */
internal fun parseModeration(event: String, payload: String, now: () -> Long): ChatEvent? {
    val m = chatJson.decodeFromString<WireModeration>(payload)
    if (m.data.isEmpty()) return null
    val at = m.timestamp ?: now()
    // The site lifts mutes itself (subscribing, donating), as "System": there is no one to name.
    val by = if (m.nick.isEmpty() || m.nick.equals("System", ignoreCase = true)) null else m.nick
    val text = when (event) {
        "MUTE" -> "${m.data} muted by ${by ?: "the site"}."
        "BAN" -> "${m.data} banned by ${by ?: "the site"}."
        "UNMUTE" -> if (by != null) "${m.data} unmuted by $by." else "${m.data} has been unmuted."
        "UNBAN" -> if (by != null) "${m.data} unbanned by $by." else "${m.data} has been unbanned."
        "ADDPHRASE" -> "Phrase \"${m.data}\" added."
        "REMOVEPHRASE" -> "Phrase \"${m.data}\" removed."
        else -> return null
    }
    val removed = if (event == "MUTE" || event == "BAN") m.data else null
    return ChatEvent.Moderation(removed, ChatLine.notice(text, at))
}

internal fun parseSpot(payload: String): Spot? = readSpot(chatJson.decodeFromString<WireSpotlight>(payload))

internal fun parseSpots(payload: String): List<Spot> =
    chatJson.decodeFromString<List<WireSpotlight>>(payload).mapNotNull(::readSpot)

private fun readSpot(s: WireSpotlight): Spot? {
    val nick = s.nick ?: s.user?.nick ?: return null
    if (s.key.isEmpty() || s.messageTimestamp == 0L) return null
    return Spot(s.key, nick, s.data, s.messageTimestamp, s.spotlightedBy)
}

/** `UNSPOTLIGHT {key}`. */
internal fun parseUnspot(payload: String): String? =
    (chatJson.parseToJsonElement(payload) as? JsonObject)?.get("key")?.let { (it as? JsonPrimitive)?.contentOrNull }
