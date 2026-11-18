package app.loopdo.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.client.auth.AuthState
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.QuickAdd
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D3 (TEST USER): quick add saves a journal line, and `t …` a task in the inbox, then closes. */
@OptIn(ExperimentalTestApi::class)
class QuickAddLiveTest {
    @Test
    fun `quick add writes a line and a task`() {
        LiveTestUser.assumeLive()
        val g = DesktopGraph(Files.createTempDirectory("loopdo-quick").toFile())
        runBlocking {
            assertTrue(g.auth.signIn(LiveTestUser.email, LiveTestUser.password).isSuccess)
            withTimeout(20_000) { g.auth.session.first { it is AuthState.SignedIn } }
            withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        }
        val tag = UUID.randomUUID().toString().take(6)
        for (input in listOf("quick line $tag", "t quick task $tag")) runComposeUiTest {
            var closed = false
            setContent { LoopDoTheme { QuickAdd(g, g.popupViewModel()) { closed = true } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasSetTextAction()).performTextInput(input)
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 15_000) { closed }
        }
        runBlocking {
            assertEquals(1L, g.sync.db.get("SELECT count(*) FROM journal_lines WHERE text = ? AND deleted_at IS NULL", listOf("quick line $tag")) { it.getLong(0)!! })
            assertEquals(1L, g.sync.db.get("SELECT count(*) FROM tasks WHERE title = ? AND due_date IS NULL", listOf("quick task $tag")) { it.getLong(0)!! })
            assertEquals("", g.drafts.line(g.today()).first, "no draft left behind")
            withTimeout(60_000) { while (g.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) delay(500) }
        }
    }
}
