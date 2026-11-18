package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import app.loopdo.client.habits.HabitDraft
import app.loopdo.client.onboarding.hasDaySettings
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** D2 Task 4 + exit checks: the laptop ticks and adds for the TEST USER; the server verifies and syncs it back. */
class TodayLiveTest {
    @Test
    fun `ticks, counts and tasks from the laptop reach the server verified`() = runBlocking {
        LiveTestUser.assumeLive()
        val dir = Files.createTempDirectory("loopdo-live-today").toFile()
        val g = DesktopGraph(dir)
        assertTrue(g.auth.signIn(LiveTestUser.email, LiveTestUser.password).isSuccess)
        withTimeout(20_000) { g.auth.session.first { it is AuthState.SignedIn } }
        withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        if (!g.sync.db.hasDaySettings().first()) {
            assertTrue(g.onboarding.submit(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0), "desktop test").isSuccess, "day settings")
            withTimeout(60_000) { g.sync.db.hasDaySettings().first { it } }
        }
        withTimeout(30_000) { while (g.anchorStore.current() == null) delay(300) } // signed server time → verified ticks

        suspend fun habit(title: String, kind: HabitKind, target: Int): UUID =
            g.sync.db.getOptional("SELECT id FROM habits WHERE title = ? AND deleted_at IS NULL", listOf(title)) { UUID.fromString(it.getString(0)!!) }
                ?: g.habitActions.createHabit(HabitDraft(title, kind, target, null, 1, Recurrence.DAILY, emptySet(), null))
        val yes = habit("laptop yes", HabitKind.YES_NO, 1)
        val count = habit("laptop count", HabitKind.COUNT, 5)
        // Repeatable: on a second run today the yes/no habit is already ticked, so untick it first.
        if (g.actions.setHabitCount(yes, 1) == null) { g.actions.untickHabit(yes); assertNotNull(g.actions.setHabitCount(yes, 1)) }
        assertNotNull(g.actions.tickHabit(count))
        val task = g.actions.addTask("laptop task ${System.currentTimeMillis()}", null, null, null)

        // Everything uploaded, then the server's verdict comes back down through PowerSync.
        withTimeout(90_000) { while (g.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) delay(500) }
        withTimeout(90_000) {
            while (g.sync.db.getOptional("SELECT verified FROM habit_logs WHERE habit_id = ? ORDER BY loopdo_date DESC LIMIT 1", listOf(yes.toString())) { it.getLong(0) ?: -1L } != 1L) delay(500)
        }
        assertEquals(1L, g.sync.db.get("SELECT count(*) FROM tasks WHERE id = ?", listOf(task.toString())) { it.getLong(0)!! })
        assertTrue(g.sync.db.get("SELECT count FROM habit_logs WHERE habit_id = ? ORDER BY loopdo_date DESC LIMIT 1", listOf(count.toString())) { it.getLong(0)!! } >= 1)
        g.sync.db.disconnect(); g.scope.cancel()
    }
}
