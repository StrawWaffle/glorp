package io.github.strawwaffle.glorp.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** chat-gui's settingssync.test.js, case for case. */
class ChatSettingsTest {
    private fun defaults() = LinkedHashMap(SETTINGS_DEFAULTS)
    private fun withValues(vararg values: Pair<String, Any>): Map<String, JsonElement> =
        defaults().apply { values.forEach { (k, v) -> put(k, json(v)) } }

    private fun json(v: Any): JsonElement = when (v) {
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { json(it!!) })
        else -> error(v)
    }

    private fun pairs(list: JsonElement?) = (list as JsonArray).associate {
        val (k, v) = it as JsonArray
        (k as JsonPrimitive).content to (v as JsonPrimitive).content
    }

    // mergeSettings

    @Test fun aCustomizedLocalValueBeatsADefaultOnTheServer() {
        val merged = mergeSettings(defaults(), withValues("showtime" to true, "maxlines" to 500))
        assertEquals(json(true), merged["showtime"])
        assertEquals(json(500), merged["maxlines"])
    }

    @Test fun aDefaultLocalValueDoesNotReplaceACustomizedServerValue() {
        assertEquals(json(true), mergeSettings(withValues("showtime" to true), defaults())["showtime"])
    }

    @Test fun theServerWinsWhenBothCustomizedTheSameValue() {
        val merged = mergeSettings(withValues("timestampformat" to "HH:mm:ss"), withValues("timestampformat" to "h:mm a"))
        assertEquals(json("HH:mm:ss"), merged["timestampformat"])
    }

    @Test fun listsAreCombined() {
        val merged = mergeSettings(withValues("ignorenicks" to listOf("a", "b")), withValues("ignorenicks" to listOf("b", "c")))
        assertEquals(json(listOf("a", "b", "c")), merged["ignorenicks"])
    }

    @Test fun pairListsAreCombinedByKeyTheServerWinning() {
        val merged = mergeSettings(
            withValues("taggednicks" to listOf(listOf("a", "red"))),
            withValues("taggednicks" to listOf(listOf("a", "blue"), listOf("b", "green"))),
        )
        assertEquals(mapOf("a" to "red", "b" to "green"), pairs(merged["taggednicks"]))
    }

    @Test fun anEmptyServerTakesTheLocalCustomizations() {
        val merged = mergeSettings(emptyMap(), withValues("ignorenicks" to listOf("a"), "showtime" to true))
        assertEquals(json(listOf("a")), merged["ignorenicks"])
        assertEquals(json(true), merged["showtime"])
    }

    @Test fun theLocalProfilesettingsValueIsNeverTaken() {
        assertFalse("profilesettings" in mergeSettings(emptyMap(), withValues("profilesettings" to true)))
    }

    @Test fun aLegacyProfileCopyWinsOnSingleValuesEvenDefaults() {
        val merged = mergeSettings(
            withValues("profilesettings" to true, "timestampformat" to "HH:mm:ss"),
            withValues("showtime" to true, "timestampformat" to "h:mm a"),
            legacy = true,
        )
        assertEquals(json(false), merged["showtime"])
        assertEquals(json("HH:mm:ss"), merged["timestampformat"])
    }

    @Test fun aLegacyProfileCopyStillCombinesLists() {
        val merged = mergeSettings(
            withValues("profilesettings" to true, "ignorenicks" to listOf("a"), "taggednicks" to listOf(listOf("a", "red"))),
            withValues(
                "ignorenicks" to listOf("x", "y"),
                "taggednicks" to listOf(listOf("a", "blue"), listOf("b", "green")),
                "highlightnicks" to listOf("h"),
            ),
            legacy = true,
        )
        assertEquals(json(listOf("a", "x", "y")), merged["ignorenicks"])
        assertEquals(mapOf("a" to "red", "b" to "green"), pairs(merged["taggednicks"]))
        assertEquals(json(listOf("h")), merged["highlightnicks"])
    }

    // sameSettings

    @Test fun missingValuesCountAsDefaults() = assertTrue(sameSettings(emptyMap(), defaults()))

    @Test fun ignoresBookkeepingKeys() = assertTrue(sameSettings(withValues("profilesettings" to true), defaults()))

    @Test fun noticesAChangedValue() = assertFalse(sameSettings(withValues("showtime" to true), defaults()))

    // resolveSettings

    private fun resolve(server: Map<String, JsonElement>, local: Map<String, JsonElement>, syncedFor: String?, pending: Boolean = false) =
        resolveSettings(server, local, "1", syncedFor, pending)

    @Test fun anUncustomizedDeviceDoesNotUploadToAnEmptyProfile() = assertFalse(resolve(emptyMap(), defaults(), null).upload)

    @Test fun anUncustomizedDeviceLeavesACustomizedProfileAlone() {
        val r = resolve(withValues("showtime" to true, "ignorenicks" to listOf("a")), defaults(), null)
        assertFalse(r.upload)
        assertEquals(json(true), r.settings["showtime"])
        assertEquals(json(listOf("a")), r.settings["ignorenicks"])
    }

    @Test fun theMainDeviceWinsOverDefaultsAPhoneUploadedFirst() {
        val server = resolve(emptyMap(), defaults(), null).settings
        val r = resolve(server, withValues("showtime" to true, "ignorenicks" to listOf("a")), null)
        assertTrue(r.upload)
        assertEquals(json(true), r.settings["showtime"])
        assertEquals(json(listOf("a")), r.settings["ignorenicks"])
    }

    @Test fun aCustomizedDeviceUploadsToAnEmptyProfile() {
        val r = resolve(emptyMap(), withValues("ignorenicks" to listOf("a")), null)
        assertTrue(r.upload)
        assertEquals(json(listOf("a")), r.settings["ignorenicks"])
    }

    @Test fun aDeviceThatHasSyncedTakesTheProfileCopy() {
        val server = withValues("ignorenicks" to listOf("a"))
        val r = resolve(server, withValues("ignorenicks" to listOf("a", "removed-elsewhere")), "1")
        assertFalse(r.upload)
        assertEquals(server, r.settings)
    }

    @Test fun aLegacyProfileCopyKeepsItsSingleValuesAndGainsLists() {
        val r = resolve(withValues("profilesettings" to true, "ignorenicks" to listOf("a")), withValues("ignorenicks" to listOf("x"), "showtime" to true), null)
        assertTrue(r.upload)
        assertEquals(json(listOf("a", "x")), r.settings["ignorenicks"])
        assertEquals(json(false), r.settings["showtime"])
        assertEquals(json(true), r.settings["profilesettings"])
    }

    @Test fun aLegacyProfileCopyIsNotUploadedWhenTheDeviceAddsNothing() {
        val r = resolve(withValues("profilesettings" to true, "ignorenicks" to listOf("a")), withValues("ignorenicks" to listOf("a"), "showtime" to true), null)
        assertFalse(r.upload)
    }

    @Test fun aSyncedDeviceWithAnEmptyProfileUploadsItsSettings() =
        assertTrue(resolve(emptyMap(), withValues("showtime" to true), "1").upload)

    private val theirs = withValues("ignorenicks" to listOf("theirs"), "taggednotes" to listOf(listOf("someone", "their note")), "showtime" to true)

    @Test fun anotherAccountsDeviceTakesTheProfileCopyAndUploadsNone() {
        val r = resolve(withValues("ignorenicks" to listOf("mine")), theirs, "2")
        assertFalse(r.upload)
        assertEquals(json(listOf("mine")), r.settings["ignorenicks"])
        assertEquals(json(emptyList<String>()), r.settings["taggednotes"])
        assertEquals(json(false), r.settings["showtime"])
    }

    @Test fun anotherAccountsDeviceFallsBackToTheDefaults() {
        val r = resolve(emptyMap(), theirs, "2")
        assertFalse(r.upload)
        assertEquals(defaults(), r.settings)
    }

    @Test fun anotherAccountsPendingChangesAreIgnored() = assertFalse(resolve(emptyMap(), theirs, "2", pending = true).upload)

    @Test fun pendingChangesOnASyncedDeviceWin() {
        val local = withValues("ignorenicks" to listOf("a", "added-offline"))
        val r = resolve(withValues("ignorenicks" to listOf("a")), local, "1", pending = true)
        assertTrue(r.upload)
        assertEquals(local, r.settings)
    }

    @Test fun pendingChangesKeepTheLegacyMark() {
        val r = resolve(withValues("profilesettings" to true), withValues("showtime" to true), "1", pending = true)
        assertEquals(json(true), r.settings["profilesettings"])
        assertEquals(json(true), r.settings["showtime"])
    }

    // Glorp's own

    @Test fun keysChatGuiDoesntKnowAreKept() {
        val server = withValues("somethingnew" to "x")
        assertEquals(json("x"), resolve(server, defaults(), null).settings["somethingnew"])
        assertEquals(json("x"), withDefaults(server)["somethingnew"])
    }

    @Test fun theSitesFormRoundTrips() {
        val settings = withValues("showtime" to true, "ignorenicks" to listOf("a"))
        assertEquals(settings, settingsFromSite(settingsToSite(settings)))
    }

    @Test fun listsMoveIntoTheSettings() {
        val prefs = MemoryChatPrefs()
        prefs.putList("ignore", listOf("someone"))
        val settings = ChatSettings(prefs)
        assertEquals(listOf("someone"), settings.list("ignorenicks"))
        assertEquals(null, prefs.get("ignore"))
        assertEquals(listOf("someone"), ChatSettings(prefs).list("ignorenicks"))
    }

    @Test fun timesAreWrittenAsMomentDoes() {
        assertEquals("09:05", formatTime(9, 5, 7, "HH:mm"))
        assertEquals("9:05:07 AM", formatTime(9, 5, 7, "h:mm:ss A"))
        assertEquals("12:00 pm", formatTime(12, 0, 0, "hh:mm a"))
        assertEquals("12 at 0", formatTime(0, 0, 0, "h [at] H"))
    }

    @Test fun theTimestampCommandChecksAsChatGuiDoes() {
        assertEquals(Command.TimestampFormat("HH:mm:ss"), parseCommand("/timestampformat HH:mm:ss", false))
        assertEquals(Command.TimestampFormat(null), parseCommand("/timestampformat", false))
        assertTrue(parseCommand("/timestampformat HH:mm [at]", false) is Command.Problem)
    }
}
