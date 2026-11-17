package app.loopdo.android.habits

import app.loopdo.android.today.HabitRow
import app.loopdo.android.today.LogRow
import app.loopdo.android.today.ScheduleRow
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.habit.habitOutcomes
import app.loopdo.shared.streak.computeStreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** The habits tab, habit detail and editor models (locked habits-tasks-v1 + app-screens-v2 screen 2). Today = sat 14 nov. */
class HabitsViewModelTest {
    private fun d(day: Int): LocalDate = LocalDate.of(2026, 11, day)
    private val today = d(14)
    private fun row(id: String, title: String = id, kind: HabitKind = HabitKind.YES_NO, target: Int = 1, archivedOn: LocalDate? = null, order: Int = 0, deleted: Boolean = false, reminder: LocalTime? = null) =
        HabitRow(id, title, kind, target, reminder, archivedOn, order, deletedAt = if (deleted) Instant.parse("2026-11-14T10:00:00Z") else null)
    private fun daily(id: String, from: Int = 1, target: Int? = null) = ScheduleRow(id, d(from), Recurrence.DAILY, emptySet(), target)
    private fun paused(id: String, from: Int) = ScheduleRow(id, d(from), Recurrence.PAUSED, emptySet())

    @Test
    fun stripMatchesOutcomes() {
        // Done 1–8 nov (7 in a row earn a freeze), 9 missed → frozen, 10 missed, 11 done, 12 paused, 13 done, 14 = today, not yet.
        val h = Habit("h", HabitKind.YES_NO, 1, listOf(
            HabitSchedule(d(1), Recurrence.DAILY), HabitSchedule(d(12), Recurrence.PAUSED), HabitSchedule(d(13), Recurrence.DAILY),
        ))
        val logs = ((1..8) + 11 + 13).map { HabitLog(d(it), 1) }
        assertEquals(
            listOf(StripCell.DONE, StripCell.FROZEN, StripCell.MISSED, StripCell.DONE, StripCell.NOT_DUE, StripCell.DONE, StripCell.TODAY),
            habitStrip(h, logs, today),
        )
        // Started on 12 nov: the days before aren't due; today already done shows as done.
        val fresh = Habit("n", HabitKind.YES_NO, 1, listOf(HabitSchedule(d(12), Recurrence.DAILY)))
        assertEquals(
            listOf(StripCell.NOT_DUE, StripCell.NOT_DUE, StripCell.NOT_DUE, StripCell.NOT_DUE, StripCell.MISSED, StripCell.DONE, StripCell.DONE),
            habitStrip(fresh, listOf(HabitLog(d(13), 1), HabitLog(d(14), 1)), today),
        )
    }

    @Test
    fun editorShowsTomorrowForTargetChange() {
        val mon = LocalDate.of(2026, 11, 16)
        assertEquals("35 → 50 from tomorrow, tue 17 nov. today and every past day keep 35.", targetChangeNote(35, 50, mon))
        assertNull(targetChangeNote(35, 35, mon))
        // A change already saved for tomorrow: the editor starts from it, and still says what it does.
        val vocab = row("v", "japanese vocab", HabitKind.COUNT, target = 35)
        val start = editorStart(vocab, listOf(daily("v"), daily("v", from = 15, target = 50)), listOf(LogRow("v", d(13), 20)), today)
        assertEquals(50, start.draft.target)
        assertEquals(35, start.todayTarget)
        assertTrue(start.hasHistory)
        assertEquals("35 → 50 from tomorrow, sun 15 nov. today and every past day keep 35.", targetChangeNote(start.todayTarget, start.draft.target, today))
    }

    @Test
    fun validationMessagesArePlain() {
        val ok = HabitDraft("kanji", HabitKind.COUNT, 50, null, 10, Recurrence.DAILY, emptySet(), null)
        val bad = listOf(
            ok.copy(title = " "), ok.copy(title = "ab"), ok.copy(title = "x".repeat(31)), ok.copy(target = 1), ok.copy(step = 0),
            ok.copy(unit = "x".repeat(17)), ok.copy(recurrence = Recurrence.WEEKDAYS), ok.copy(recurrence = Recurrence.PAUSED),
        )
        bad.forEach { d ->
            val message = runCatching { checkDraft(d) }.exceptionOrNull()?.message
            assertTrue("refused: $d", message != null)
            assertTrue("plain words, lowercase, a full stop: $message", Regex("^[a-z\\[].*\\.$").matches(message!!))
            listOf("exception", "null", "requirement", "illegal").forEach { assertTrue("no jargon: $message", it !in message.lowercase()) }
        }
    }

    @Test
    fun listSplitsActivePausedArchivedAndHidesDeleted() {
        val list = buildHabitsList(
            habits = listOf(row("a", order = 1), row("b", order = 0), row("p"), row("x", archivedOn = d(10)), row("gone", deleted = true)),
            schedules = listOf(daily("a"), daily("b"), daily("p"), paused("p", 10), daily("p", from = 20), daily("x"), daily("gone")),
            logs = listOf(LogRow("a", d(13), 1)),
            today = today,
        )
        assertEquals((8..14).map(::d), list.stripDays)
        assertEquals(listOf("b", "a"), list.active.map { it.id }) // the user's order
        assertEquals(listOf("p"), list.paused.map { it.id })
        assertEquals("until thu 19 nov", list.paused.single().note)
        assertEquals(listOf("x"), list.archived.map { it.id })
        assertEquals("since tue 10 nov", list.archived.single().note)
    }

    @Test
    fun detailNumbersMatchSharedStreak() {
        val gymDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val gym = row("gym", "gym", reminder = LocalTime.of(18, 0))
        val schedules = listOf(ScheduleRow("gym", d(2), Recurrence.WEEKDAYS, gymDays))
        val done = listOf(2, 4, 9, 11, 13).map { LogRow("gym", d(it), 1) } // 6 nov missed
        val detail = habitDetail(gym, schedules, done, today, YearMonth.of(2026, 11))!!
        assertEquals("mon · wed · fri @18:00", detail.scheduleLine)
        val expected = computeStreak(habitOutcomes(
            Habit("gym", HabitKind.YES_NO, 1, listOf(HabitSchedule(d(2), Recurrence.WEEKDAYS, gymDays))),
            done.map { HabitLog(it.date, it.count) }, today,
        ))
        assertEquals(listOf(expected.current, expected.best, expected.freezesHeld), listOf(detail.streak, detail.best, detail.freezes))
        assertEquals(83, detail.donePercent) // 5 of 6 due days
        val cell = detail.cells.associateBy { it.date }
        assertEquals(CellState.DONE, cell[d(4)]!!.state)
        assertEquals(CellState.MISSED, cell[d(6)]!!.state)
        assertEquals(CellState.OFF, cell[d(5)]!!.state)
        assertEquals(CellState.OFF, cell[d(1)]!!.state) // before it started
        assertEquals(CellState.FUTURE, cell[d(16)]!!.state)
        assertTrue(cell[d(14)]!!.today)
        assertEquals(30, detail.cells.size)
        assertEquals(listOf(5, 6, 0), listOf(detail.monthDone, detail.monthDue, detail.monthFrozen))
        assertEquals(d(16), detail.nextDue) // sat today isn't a gym day; next is mon
        assertEquals(d(2), detail.since)
        assertEquals(5, detail.totalDone)
    }
}
