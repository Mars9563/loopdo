package app.loopdo.desktop

import app.loopdo.client.habits.HabitsData
import app.loopdo.client.today.HabitRow
import app.loopdo.client.today.LogRow
import app.loopdo.client.today.ScheduleRow
import app.loopdo.desktop.ui.HabitsPane
import app.loopdo.desktop.ui.Place
import app.loopdo.desktop.ui.Shell
import app.loopdo.desktop.ui.savedStatus
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertTrue

/** D4: ~/habits renders the locked desktop-screens-v1 F with sample history (PNG kept for a look). */
class HabitsPaneRenderTest {
    companion object {
        val today: LocalDate = LocalDate.of(2026, 11, 19)
        fun sample(): HabitsData {
            val since = today.minusDays(120)
            val habits = listOf(
                HabitRow("h1", "meditate", HabitKind.YES_NO, 1, LocalTime.of(7, 30), null, 0),
                HabitRow("h2", "gym", HabitKind.YES_NO, 1, LocalTime.of(18, 0), null, 1),
                HabitRow("h3", "water", HabitKind.COUNT, 8, null, null, 2, unit = "glasses"),
                HabitRow("h4", "grammar", HabitKind.YES_NO, 1, null, null, 3),
            )
            val schedules = listOf(
                ScheduleRow("h1", since, Recurrence.DAILY, emptySet()), ScheduleRow("h2", since, Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
                ScheduleRow("h3", since, Recurrence.DAILY, emptySet(), 8), ScheduleRow("h4", since, Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            )
            val r = java.util.Random(7)
            val logs = (1..119L).flatMap { back -> val d = today.minusDays(back)
                listOfNotNull(LogRow("h1", d, if (r.nextInt(10) < 9) 1 else 0), LogRow("h2", d, if (r.nextInt(10) < 6) 1 else 0), LogRow("h3", d, r.nextInt(9)), LogRow("h4", d, 1)) }
            return HabitsData(today, habits, schedules, logs)
        }
    }

    @Test
    fun `habits renders with sample history`() {
        val out = File("build/renders/habits.png")
        selfCheck(out) { Shell(Place.HABITS, {}, savedStatus("all saved · just now")) { HabitsPane(sample(), "h1", {}, {}, {}, {}) } }
        assertTrue(out.length() > 10_000)
    }
}
