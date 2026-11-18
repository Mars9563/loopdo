package app.loopdo.android.notify

import app.loopdo.shared.remind.Ping
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import app.loopdo.shared.remind.reminderKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ReminderRulesTest {
    private val d18 = LocalDate.of(2026, 11, 18)
    private val d19 = LocalDate.of(2026, 11, 19)
    private val t0 = Instant.parse("2026-11-18T12:00:00Z")
    private fun r(kind: ReminderKind, id: String?, ping: Ping = Ping.AT, date: LocalDate = d18, at: Instant = t0) =
        Reminder(reminderKey(kind, id, date, ping), kind, id, date, at, ping)
    private fun yesNo(count: Int = 0) = ItemState.Habit("gym", yesNo = true, count = count, target = 1, step = 1, dueToday = true)
    private fun water(count: Int, step: Int = 1, title: String = "water", target: Int = 8) = ItemState.Habit(title, yesNo = false, count = count, target = target, step = step, dueToday = true)

    @Test fun yesNoHabitBeforeAndAt() {
        val before = decide(r(ReminderKind.HABIT, "gym", Ping.BEFORE), yesNo(), d18, quiet = false)!!
        assertEquals("gym in 10 min", before.title)
        assertEquals(listOf(Button("[tick]", "tick")), before.buttons)
        assertEquals(CH_HABITS, before.channel)
        assertEquals("gym · now", decide(r(ReminderKind.HABIT, "gym"), yesNo(), d18, quiet = false)!!.title)
    }

    @Test fun countHabitShowsProgressAndStep() {
        val w = decide(r(ReminderKind.HABIT, "water"), water(3), d18, quiet = false)!!
        assertEquals("water · 3/8", w.title)
        assertEquals(listOf(Button("[+1]", "plus", 1)), w.buttons)
        val k = decide(r(ReminderKind.HABIT, "kanji", Ping.BEFORE), water(0, step = 10, title = "kanji", target = 50), d18, quiet = false)!!
        assertEquals("kanji in 10 min · 0/50", k.title)
        assertEquals(listOf(Button("[+10]", "plus", 10)), k.buttons)
    }

    @Test fun doneOrGoneShowsNothing() {
        assertNull(decide(r(ReminderKind.HABIT, "gym"), yesNo(count = 1), d18, quiet = false))
        assertNull(decide(r(ReminderKind.HABIT, "water"), water(8), d18, quiet = false))
        assertNull(decide(r(ReminderKind.HABIT, "gym"), yesNo().copy(dueToday = false), d18, quiet = false))
        assertNull(decide(r(ReminderKind.HABIT, "gym"), ItemState.Gone, d18, quiet = false))
        assertNull(decide(r(ReminderKind.TASK, "rent"), ItemState.Task("pay rent", LocalTime.of(18, 30), done = true), d18, quiet = false))
    }

    @Test fun endedDayShowsNothing() {
        assertNull(decide(r(ReminderKind.HABIT, "gym"), yesNo(), d19, quiet = false))
        assertNull(decide(r(ReminderKind.HABIT, "gym"), yesNo(), null, quiet = false))
    }

    @Test fun taskButtons() {
        val s = decide(r(ReminderKind.TASK, "rent", Ping.BEFORE), ItemState.Task("pay rent", LocalTime.of(18, 30), done = false), d18, quiet = false)!!
        assertEquals("pay rent · 18:30", s.title)
        assertEquals("in 10 min", s.body)
        assertEquals(listOf(Button("[done]", "done"), Button("[snooze 30m]", "snooze")), s.buttons)
        assertEquals(CH_TASKS, s.channel)
        assertEquals("now", decide(r(ReminderKind.TASK, "rent", Ping.SNOOZE), ItemState.Task("pay rent", LocalTime.of(18, 30), false), d18, false)!!.body)
    }

    @Test fun journalOnlyWhenEmpty() {
        assertNull(decide(r(ReminderKind.JOURNAL, null), ItemState.Day(emptyList(), journalWritten = true), d18, quiet = false))
        val j = decide(r(ReminderKind.JOURNAL, null), ItemState.Day(listOf("gym"), journalWritten = false), d18, quiet = false)!!
        assertEquals("nothing written today.", j.title)
        assertEquals(listOf(Button("[write]", "write")), j.buttons)
        assertEquals(CH_JOURNAL, j.channel)
    }

    @Test fun dayEndWording() {
        val both = decide(r(ReminderKind.DAY_END, null), ItemState.Day(listOf("gym", "kanji"), journalWritten = false), d18, quiet = false)!!
        assertEquals("1h left: 2 habits + journal not done", both.title)
        assertEquals("gym, kanji", both.body)
        assertEquals(CH_DAY_END, both.channel)
        assertEquals(emptyList(), both.buttons)
        assertEquals("1h left: 1 habit not done", decide(r(ReminderKind.DAY_END, null), ItemState.Day(listOf("gym"), true), d18, false)!!.title)
        assertEquals("1h left: journal not done", decide(r(ReminderKind.DAY_END, null), ItemState.Day(emptyList(), false), d18, false)!!.title)
        assertNull(decide(r(ReminderKind.DAY_END, null), ItemState.Day(emptyList(), journalWritten = true), d18, quiet = false))
    }

    @Test fun dayEndQuietUsesNightChannel() {
        assertEquals(CH_DAY_END_NIGHT, decide(r(ReminderKind.DAY_END, null), ItemState.Day(listOf("gym"), false), d18, quiet = true)!!.channel)
        assertEquals(CH_HABITS, decide(r(ReminderKind.HABIT, "gym"), yesNo(), d18, quiet = true)!!.channel) // only the day-end warning goes quiet
    }

    @Test fun catchUpOnePerItemOnlyToday() {
        val before = r(ReminderKind.HABIT, "gym", Ping.BEFORE, at = t0.minusSeconds(1200))
        val at = r(ReminderKind.HABIT, "gym", Ping.AT, at = t0.minusSeconds(600))
        val future = r(ReminderKind.HABIT, "kanji", Ping.AT, at = t0.plusSeconds(600))
        val yesterday = r(ReminderKind.HABIT, "old", Ping.AT, date = d18.minusDays(1), at = t0.minusSeconds(86_400))
        assertEquals(listOf(at), catchUp(listOf(before, at, future, yesterday), shown = emptySet(), now = t0, today = d18))
        assertEquals(emptyList(), catchUp(listOf(before, at), shown = setOf(at.key), now = t0, today = d18))
        assertEquals(emptyList(), catchUp(listOf(before, at), shown = emptySet(), now = t0, today = null))
    }

    @Test fun idsShareAcrossPings() {
        assertEquals(notificationId(r(ReminderKind.HABIT, "gym", Ping.BEFORE)), notificationId(r(ReminderKind.HABIT, "gym", Ping.AT)))
        assertEquals(notificationId(r(ReminderKind.TASK, "rent", Ping.AT)), notificationId(r(ReminderKind.TASK, "rent", Ping.SNOOZE)))
        assertNotEquals(notificationId(r(ReminderKind.HABIT, "gym")), notificationId(r(ReminderKind.HABIT, "gym", date = d19)))
        assertNotEquals(notificationId(r(ReminderKind.HABIT, "gym")), notificationId(r(ReminderKind.HABIT, "kanji")))
    }

    @Test fun weekReviewWording() {
        val r = r(ReminderKind.WEEK_REVIEW, null)
        val s = decide(r, ItemState.Week(47, 33, 40, listOf("gym 3/4"), opened = false), d18, quiet = false)!!
        assertEquals("week 47 review: 33/40 done", s.title)
        assertEquals("gym 3/4 · pick next week's focus", s.body)
        assertEquals(listOf(Button("[review >]", "review")), s.buttons)
        assertEquals(CH_STATS, s.channel)
        assertEquals("pick next week's focus", decide(r, ItemState.Week(47, 1, 2, emptyList(), false), d18, false)!!.body)
    }

    @Test fun openedReviewShowsNothing() {
        assertNull(decide(r(ReminderKind.WEEK_REVIEW, null), ItemState.Week(47, 33, 40, emptyList(), opened = true), d18, quiet = false))
    }
}
