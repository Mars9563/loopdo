package app.loopdo.shared.stats

import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverviewTest {
    private val today = LocalDate.of(2026, 11, 18)
    private fun ago(n: Int) = today.minusDays(n.toLong())
    private fun daily(from: LocalDate) = listOf(HabitSchedule(from, Recurrence.DAILY))
    private fun habit(id: String, from: LocalDate = LocalDate.of(2026, 1, 1), order: Int = 0) = StatHabit(Habit(id, HabitKind.YES_NO, 1, daily(from)), id, order)
    private fun input(habits: List<StatHabit>, logs: Map<String, List<HabitLog>> = emptyMap(), active: Set<LocalDate> = emptySet()) =
        WeekInput(habits, logs, emptyList(), active)

    @Test fun pixelsMoodAndBlank() {
        val p = yearPixels(2026, PixelMode.MOOD, mapOf(LocalDate.of(2026, 3, 5) to 4, ago(0) to 2), input(emptyList()), today)
        assertEquals(4, p[2][4])
        assertEquals(2, p[10][17])
        assertEquals(0, p[0][0])
        assertEquals(0, p[1][30]) // feb 31 doesn't exist
    }

    @Test fun pixelsPerfectAndActive() {
        val h = habit("a", from = LocalDate.of(2026, 11, 1))
        val logs = mapOf("a" to listOf(HabitLog(LocalDate.of(2026, 11, 2), 1)))
        val active = setOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))
        val p = yearPixels(2026, PixelMode.PERFECT, emptyMap(), input(listOf(h), logs, active), today)
        assertEquals(2, p[10][1]) // 2 nov: perfect
        assertEquals(1, p[10][2]) // 3 nov: active, not perfect
        assertEquals(0, p[10][3]) // 4 nov: neither
        assertEquals(0, p[11][0]) // the future
    }

    @Test fun moodVsHabitsHiddenUnder30Days() {
        val moods = (1..29).associate { ago(it) to 3 }
        assertNull(moodVsHabits(moods, emptySet(), input(listOf(habit("gym"))), today))
    }

    @Test fun moodVsHabitsDiffAndNoJournalRow() {
        // 40 days with a mood: gym done on 20 of them (mood 4), missed on 20 (mood 3); journal written on the gym days only.
        val days = (1..40).map { ago(it) }
        val gymDays = days.filterIndexed { i, _ -> i % 2 == 0 }
        val moods = days.associateWith { if (it in gymDays) 4 else 3 }
        val logs = mapOf("gym" to gymDays.map { HabitLog(it, 1) })
        val rows = moodVsHabits(moods, gymDays.toSet(), input(listOf(habit("gym")), logs), today)!!
        assertEquals(listOf("gym" to 1.0, "no journal" to -1.0), rows.map { it.label to it.diff })
    }

    @Test fun rowNeedsFiveDaysEachSide() {
        val days = (1..40).map { ago(it) }
        val moods = days.associateWith { 3 }
        val logs = mapOf("gym" to days.take(4).map { HabitLog(it, 1) }) // done on only 4 days
        val rows = moodVsHabits(moods, days.toSet(), input(listOf(habit("gym")), logs), today)!!
        assertEquals(emptyList(), rows) // gym: too few done days; no journal: no day without one
    }

    @Test fun completion30dAndAll() {
        val h = habit("kanji", from = ago(59), order = 1)
        val logs = mapOf("kanji" to (1..59).filter { it <= 30 || it % 2 == 0 }.map { HabitLog(ago(it), 1) }) // 30 straight, then every other day
        val last30 = completion(input(listOf(h), logs), today, last30 = true)
        assertEquals(listOf(Completion("kanji", 100)), last30) // today is still open: not counted
        val all = completion(input(listOf(h), logs), today, last30 = false)
        assertEquals(listOf(Completion("kanji", 75)), all) // 44 of 59
    }
}
