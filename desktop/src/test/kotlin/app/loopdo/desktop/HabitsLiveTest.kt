package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import app.loopdo.client.habits.HabitDraft
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** D4 (TEST USER): a habit made, edited, archived and restored on the laptop arrives the same on another device. */
class HabitsLiveTest {
    private suspend fun start(dir: File): DesktopGraph {
        val g = DesktopGraph(dir)
        if (g.auth.session.first { it !is AuthState.Loading } !is AuthState.SignedIn) g.auth.signIn(LiveTestUser.email, LiveTestUser.password)
        withTimeout(20_000) { g.auth.session.first { it is AuthState.SignedIn } }
        withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        withTimeout(30_000) { while (g.anchorStore.current() == null) delay(300) }
        return g
    }
    private suspend fun DesktopGraph.idle() = withTimeout(90_000) { while (sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) delay(400) }

    @Test
    fun `habits round-trip`() = runBlocking {
        LiveTestUser.assumeLive()
        val a = start(Files.createTempDirectory("loopdo-h1").toFile())
        val title = "laptop h ${UUID.randomUUID().toString().take(5)}"
        val id = a.habitActions.createHabit(HabitDraft(title, HabitKind.COUNT, 4, "pages", 2, Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), null))
        a.habitActions.editHabit(id, HabitDraft(title, HabitKind.COUNT, 6, "pages", 2, Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), java.time.LocalTime.of(21, 0)))
        a.idle()
        val b = start(Files.createTempDirectory("loopdo-h2").toFile())
        withTimeout(30_000) { while (b.sync.db.get("SELECT count(*) FROM habits WHERE id = ?", listOf(id.toString())) { it.getLong(0)!! } == 0L) delay(250) }
        assertEquals("21:00", b.sync.db.get("SELECT substr(reminder_time, 1, 5) FROM habits WHERE id = ?", listOf(id.toString())) { it.getString(0)!! })
        // The new target is a schedule version from tomorrow (today keeps 4), as on the phone.
        withTimeout(30_000) {
            while (b.sync.db.getAll("SELECT coalesce(target, -1) FROM habit_schedules WHERE habit_id = ?", listOf(id.toString())) { it.getLong(0)!! }.none { it == 6L }) delay(250)
        }
        // Archive, then restore (archiving drops planned changes — the phone's rule).
        a.habitActions.archiveHabit(id); a.habitActions.restoreHabit(id); a.idle()
        withTimeout(30_000) { while (b.sync.db.get("SELECT coalesce(archived_on, '') FROM habits WHERE id = ?", listOf(id.toString())) { it.getString(0)!! } != "") delay(250) }
        // Tidy: earlier runs' copies too (delete forever = hidden).
        a.sync.db.getAll("SELECT id FROM habits WHERE title LIKE 'laptop h %' AND deleted_at IS NULL") { UUID.fromString(it.getString(0)!!) }.forEach { a.habitActions.hideHabit(it) }
        a.idle()
        listOf(a, b).forEach { it.sync.db.disconnect(); it.sync.db.close(); it.scope.cancel() }
    }
}
