package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import app.loopdo.client.stats.StatsData
import app.loopdo.client.stats.buildStats
import app.loopdo.shared.day.dateResolver
import app.loopdo.shared.stats.FocusKind
import app.loopdo.shared.stats.PixelMode
import app.loopdo.shared.stats.weekStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D5 (TEST USER): two laptops compute the same stats from the same data; the focus is set only on Sunday or Monday. */
class StatsLiveTest {
    private suspend fun start(dir: File): DesktopGraph {
        val g = DesktopGraph(dir)
        if (g.auth.session.first { it !is AuthState.Loading } !is AuthState.SignedIn) g.auth.signIn(LiveTestUser.email, LiveTestUser.password)
        withTimeout(20_000) { g.auth.session.first { it is AuthState.SignedIn } }
        withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        withTimeout(30_000) { while (g.anchorStore.current() == null) delay(300) }
        return g
    }

    @Test
    fun `stats match across devices and the focus window holds`() = runBlocking {
        LiveTestUser.assumeLive()
        val a = start(Files.createTempDirectory("loopdo-s1").toFile())
        val b = start(Files.createTempDirectory("loopdo-s2").toFile())
        val ra = StatsData(a.sync.db).rows().first { it.versions.isNotEmpty() }
        val rb = StatsData(b.sync.db).rows().first { it.versions.isNotEmpty() }
        val today = dateResolver(ra.versions)(a.trustedClock.now().instant)
        val sa = buildStats(ra, today, today.year, PixelMode.MOOD, true)
        val sb = buildStats(rb, today, today.year, PixelMode.MOOD, true)
        assertEquals(sa.week, sb.week)
        assertEquals(sa.completion, sb.completion)
        assertTrue(sa.pixels.contentDeepEquals(sb.pixels))

        val next = weekStart(today).plusWeeks(1)
        val habit = ra.input.habits.first().habit.id
        val r = a.focusActions.add(next, FocusKind.HABIT, habit, "laptop focus", 3, null)
        if (today.dayOfWeek == DayOfWeek.SUNDAY || today.dayOfWeek == DayOfWeek.MONDAY) {
            assertTrue(r.isSuccess, "sun/mon: allowed"); a.focusActions.remove(r.getOrThrow())
        } else {
            assertTrue(r.isFailure, "only sunday or monday"); println("refused with: ${r.exceptionOrNull()?.message}")
        }
        listOf(a, b).forEach { it.sync.db.disconnect(); it.sync.db.close(); it.scope.cancel() }
    }
}
