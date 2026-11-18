package app.loopdo.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.client.time.SignedAnchor
import app.loopdo.desktop.shell.SingleInstance
import app.loopdo.desktop.store.FileAnchorStore
import app.loopdo.desktop.ui.BlockedScreen
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.shared.time.TimeAnchor
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D2 final review, items 1–3. */
class ReviewFixesTest {
    private val dir: File = Files.createTempDirectory("loopdo-fixes").toFile()

    @Test
    fun `the time anchor is saved and read as one piece`() {
        val f = File(dir, "anchor.json")
        val store = FileAnchorStore(f)
        fun anchor(i: Long) = SignedAnchor(TimeAnchor(Instant.ofEpochSecond(1_800_000_000L + i), i, i.toInt()), "sig-$i")
        store.save(anchor(0))
        val torn = AtomicInteger()
        val stop = CountDownLatch(1)
        val reader = thread { while (stop.count > 0) { val a = store.current()!!; if (a.sig != "sig-${a.anchor.elapsedRealtimeMs}" || a.anchor.bootCount.toLong() != a.anchor.elapsedRealtimeMs) torn.incrementAndGet() } }
        for (i in 1L..400L) store.save(anchor(i))
        stop.countDown(); reader.join()
        assertEquals(0, torn.get(), "a reader saw parts of two anchors")
        assertEquals(anchor(400), FileAnchorStore(f).current())
    }

    @Test
    fun `two launches at the same moment never both serve`() {
        repeat(20) { round ->
            val d = File(dir, "race$round").apply { mkdirs() }
            val served = AtomicInteger()
            val go = CountDownLatch(1)
            val insts = List(2) { SingleInstance(File(d, "loopdo.sock"), File(d, "loopdo.lock")) }
            val ts = insts.map { s -> thread { go.await(); if (s.tryServe { }) served.incrementAndGet() } }
            go.countDown(); ts.forEach { it.join() }
            assertEquals(1, served.get(), "round $round")
            insts.forEach { it.close() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the account-switch refusal offers a way out`() = runComposeUiTest {
        var signedOut = false
        setContent { LoopDoTheme { BlockedScreen("this laptop still has changes from another account…") { signedOut = true } } }
        onNodeWithText("[sign out]").performClick()
        assertTrue(signedOut)
    }
}
