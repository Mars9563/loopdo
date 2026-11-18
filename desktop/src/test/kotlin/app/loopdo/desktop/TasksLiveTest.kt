package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import app.loopdo.client.tasks.TaskDraft
import app.loopdo.shared.task.RepeatRule
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

/** D4 (TEST USER): lists, steps, a series edited "this and future", and deleting a list (its tasks → inbox). */
class TasksLiveTest {
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
    fun `tasks round-trip`() = runBlocking {
        LiveTestUser.assumeLive()
        val a = start(Files.createTempDirectory("loopdo-t1").toFile())
        val tag = UUID.randomUUID().toString().take(5)
        val list = a.taskActions.saveList(null, "laptop $tag")
        val task = a.taskActions.saveTask(null, TaskDraft("in list $tag", "a note", null, null, list, null))
        val step = a.taskActions.addSubtask(task, "first step")
        a.taskActions.toggleSubtask(step)
        val series = a.taskActions.saveTask(null, TaskDraft("weekly $tag", null, a.today(), null, null, RepeatRule.Weekly(setOf(DayOfWeek.MONDAY, a.today().dayOfWeek))))
        val series2 = a.taskActions.editSeries(series, TaskDraft("weekly renamed $tag", null, a.today(), null, null, RepeatRule.Weekly(setOf(a.today().dayOfWeek))))
        a.taskActions.deleteList(list)
        a.idle()
        val b = start(Files.createTempDirectory("loopdo-t2").toFile())
        withTimeout(30_000) { while (b.sync.db.get("SELECT count(*) FROM tasks WHERE id = ?", listOf(task.toString())) { it.getLong(0)!! } == 0L) delay(250) }
        assertEquals("", b.sync.db.get("SELECT coalesce(list_id, '') FROM tasks WHERE id = ?", listOf(task.toString())) { it.getString(0)!! }, "the deleted list's task is in the inbox")
        assertEquals(1L, b.sync.db.get("SELECT done FROM subtasks WHERE id = ?", listOf(step.toString())) { it.getLong(0)!! })
        withTimeout(30_000) { while (b.sync.db.get("SELECT count(*) FROM tasks WHERE title = ?", listOf("weekly renamed $tag")) { it.getLong(0)!! } == 0L) delay(250) }
        // tidy
        a.taskActions.deleteTask(task); runCatching { a.taskActions.stopRepeating(series2) }; a.idle()
        listOf(a, b).forEach { it.sync.db.disconnect(); it.sync.db.close(); it.scope.cancel() }
    }
}
