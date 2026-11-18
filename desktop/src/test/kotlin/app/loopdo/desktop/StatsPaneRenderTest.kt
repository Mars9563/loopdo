package app.loopdo.desktop

import app.loopdo.client.stats.StatsRows
import app.loopdo.client.stats.buildStats
import app.loopdo.desktop.ui.Place
import app.loopdo.desktop.ui.Shell
import app.loopdo.desktop.ui.StatsActions
import app.loopdo.desktop.ui.StatsPane
import app.loopdo.desktop.ui.savedStatus
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.stats.PixelMode
import app.loopdo.shared.stats.StatHabit
import app.loopdo.shared.stats.WeekInput
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/** D5: ~/stats renders (sample year, an empty account, a late-December week) without the laptop computing anything itself. */
class StatsPaneRenderTest {
    object NoOp : StatsActions { override fun setMode(m: PixelMode) {}; override fun shiftYear(by: Int) {}; override fun setLast30(on: Boolean) {}; override fun openReview() {} }

    private fun rows(today: LocalDate, empty: Boolean = false): StatsRows {
        if (empty) return StatsRows(emptyList(), WeekInput(emptyList(), emptyMap(), emptyList(), emptySet()), emptyMap(), emptySet(), emptyList())
        val since = today.minusDays(200)
        fun h(id: String, rec: Recurrence, days: Set<DayOfWeek> = emptySet()) = Habit(id, HabitKind.YES_NO, 1, listOf(HabitSchedule(since, rec, days)))
        val habits = listOf(StatHabit(h("m", Recurrence.DAILY), "meditate", 0), StatHabit(h("g", Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), "gym", 1), StatHabit(h("k", Recurrence.DAILY), "kanji", 2))
        val r = java.util.Random(3)
        val logs = habits.associate { sh -> sh.habit.id to (1..200L).filter { r.nextInt(10) < 8 }.map { HabitLog(today.minusDays(it), 1) } }
        val moods = (1..120L).filter { r.nextInt(10) < 7 }.associate { today.minusDays(it) to 1 + r.nextInt(5) }
        return StatsRows(emptyList(), WeekInput(habits, logs, emptyList(), (1..200L).map { today.minusDays(it) }.toSet()), moods, moods.keys, emptyList())
    }

    private fun render(name: String, today: LocalDate, empty: Boolean = false) {
        val s = buildStats(rows(today, empty), today, today.year, PixelMode.MOOD, last30 = true)
        val out = File("build/renders/$name.png")
        selfCheck(out) { Shell(Place.STATS, {}, savedStatus("all saved · just now")) { StatsPane(s, listOf("gym 3×  3/3 ✓", "pay rent  done tue ✓"), 2, NoOp) } }
        assertTrue(out.length() > 10_000)
    }

    @Test fun `stats renders a sample year`() = render("stats", LocalDate.of(2026, 11, 19))
    @Test fun `stats renders an empty account`() = render("stats-empty", LocalDate.of(2026, 11, 19), empty = true)
    @Test fun `stats renders a week across new year`() = render("stats-newyear", LocalDate.of(2027, 1, 1))
}
