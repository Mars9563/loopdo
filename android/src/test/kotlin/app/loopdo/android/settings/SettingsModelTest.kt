package app.loopdo.android.settings

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.remind.ReminderPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ~/settings (locked settings-v1 A/B/D) as plain data. Now = wed 18 nov 2026 16:50 IST. */
class SettingsModelTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-18T11:20:00Z")
    private val current = DaySettingsVersion(Instant.EPOCH, DaySettings(ist, LocalTime.of(4, 0)), null)
    private val waiting = DaySettingsVersion(Instant.parse("2026-11-18T22:30:00Z"), DaySettings(ist, LocalTime.of(6, 0)), null) // thu 19 nov 04:00 IST

    @Test fun pendingChangeLine() {
        assertNull(pendingLine(listOf(current), now))
        assertEquals("from thu 19 nov 04:00: day ends 06:00", pendingLine(listOf(current, waiting), now))
        val moved = DaySettingsVersion(waiting.effectiveAt, DaySettings(ZoneId.of("Asia/Tokyo"), LocalTime.of(4, 0)), null)
        assertEquals("from thu 19 nov 04:00: time zone asia/tokyo", pendingLine(listOf(current, moved), now))
    }

    @Test fun keepRestoresCurrent() {
        assertEquals(current.settings, inForceNow(listOf(current, waiting), now))
        assertEquals("keep 04:00", keepLabel(listOf(current, waiting), now))
        assertNull(keepLabel(listOf(current), now))
    }

    @Test fun prefsWriteKeepsOtherKeys() {
        val before = """{"theme":"glass","reminders":{"habits":true}}"""
        val after = Json.parseToJsonElement(withPrefs(before, ReminderPrefs(habits = false, journalAt = LocalTime.of(22, 30), dayEndLead = Duration.ofMinutes(30)))).jsonObject
        assertEquals("glass", after.getValue("theme").jsonPrimitive.content)
        val r = after.getValue("reminders").jsonObject
        assertEquals("false", r.getValue("habits").jsonPrimitive.content)
        assertEquals("22:30", r.getValue("journal").jsonObject.getValue("at").jsonPrimitive.content)
        assertEquals("30", r.getValue("dayEnd").jsonObject.getValue("leadMinutes").jsonPrimitive.content)
        assertEquals(ReminderPrefs(habits = false, journalAt = LocalTime.of(22, 30), dayEndLead = Duration.ofMinutes(30)), app.loopdo.android.notify.reminderPrefsFromSettings(withPrefs(before, ReminderPrefs(habits = false, journalAt = LocalTime.of(22, 30), dayEndLead = Duration.ofMinutes(30)))))
        assertTrue(withPrefs(null, ReminderPrefs()).contains("\"reminders\""))
    }

    @Test fun adminRowOnlyForAdmin() {
        assertNull(adminRow(isAdmin = false, signupsOpen = true))
        assertEquals("[open]", adminRow(isAdmin = true, signupsOpen = true))
        assertEquals("[closed]", adminRow(isAdmin = true, signupsOpen = false))
        assertEquals("[…]", adminRow(isAdmin = true, signupsOpen = null)) // not loaded yet (needs the network)
    }

    @Test fun deleteButtonNeedsTheWord() {
        assertFalse(deleteEnabled(""))
        assertFalse(deleteEnabled("delet"))
        assertTrue(deleteEnabled("delete"))
        assertTrue(deleteEnabled(" Delete "))
    }

    @Test fun leadChoicesCycle() {
        assertEquals(Duration.ofMinutes(120), nextLead(Duration.ofMinutes(60)))
        assertEquals(Duration.ofMinutes(30), nextLead(Duration.ofMinutes(120)))
        assertEquals("1h", leadLabel(Duration.ofMinutes(60)))
        assertEquals("30m", leadLabel(Duration.ofMinutes(30)))
    }

    @Test fun aWaitingChangeEqualToTheCurrentIsNoChange() {
        val same = DaySettingsVersion(waiting.effectiveAt, current.settings, null) // what [keep] leaves behind
        assertNull(pendingLine(listOf(current, same), now))
        assertNull(keepLabel(listOf(current, same), now))
    }

    @Test fun aChangeBuildsOnTheWaitingOne() {
        val zoneWaiting = DaySettingsVersion(waiting.effectiveAt, DaySettings(ZoneId.of("Asia/Tokyo"), LocalTime.of(4, 0)), null)
        assertEquals(DaySettings(ZoneId.of("Asia/Tokyo"), LocalTime.of(6, 0)), withDayEnd(listOf(current, zoneWaiting), now, 6))
        assertEquals(DaySettings(ZoneId.of("Asia/Tokyo"), LocalTime.of(6, 0)), withZone(listOf(current, waiting), now, "Asia/Tokyo"))
        assertEquals(DaySettings(ist, LocalTime.of(5, 0)), withDayEnd(listOf(current), now, 5))
    }

    @Test fun versionLabelIsMajorMinor() {
        assertEquals("v0.9", versionLabel("0.9.0"))
        assertEquals("v1.2", versionLabel("1.2.3"))
    }
}
