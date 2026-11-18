package app.loopdo.android.notify

import app.loopdo.shared.remind.ReminderPrefs
import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** profiles.settings JSON → the owner's reminder settings (M9); anything missing or odd = the M7 defaults. */
class ReminderPrefsJsonTest {
    @Test fun missingOrBrokenIsDefaults() {
        assertEquals(ReminderPrefs(), reminderPrefsFromSettings(null))
        assertEquals(ReminderPrefs(), reminderPrefsFromSettings("{}"))
        assertEquals(ReminderPrefs(), reminderPrefsFromSettings("not json"))
        assertEquals(ReminderPrefs(), reminderPrefsFromSettings("""{"reminders":"x"}"""))
    }

    @Test fun readsEveryKey() {
        val p = reminderPrefsFromSettings("""{"theme":"x","reminders":{"habits":false,"tasks":true,"journal":{"on":true,"at":"22:30"},"dayEnd":{"on":false,"leadMinutes":120},"weekReview":{"on":true,"at":"19:00"}}}""")
        assertEquals(ReminderPrefs(habits = false, journalAt = LocalTime.of(22, 30), dayEnd = false, dayEndLead = Duration.ofMinutes(120), weekReviewAt = LocalTime.of(19, 0)), p)
    }
}
