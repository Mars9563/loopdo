package app.loopdo.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.desktop.journal.compressPhoto
import app.loopdo.desktop.shell.Command
import app.loopdo.desktop.shell.route
import app.loopdo.desktop.ui.JournalUi
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.Writer
import app.loopdo.desktop.ui.quickAllowed
import app.loopdo.desktop.ui.writerDate
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D3 final review, items 1–4. */
@OptIn(ExperimentalTestApi::class)
class D3FixesTest {
    private val day = LocalDate.of(2026, 11, 19)

    /** A draft store like the laptop's: a version that moves on every save. */
    class FakeUi(var refuse: String? = null) : JournalUi {
        val drafts = mutableMapOf<LocalDate, String>()
        override val draftVersion = MutableStateFlow(0L)
        val saved = mutableListOf<Pair<LocalDate, String>>()
        override fun draftFor(date: LocalDate) = drafts[date].orEmpty()
        override fun draftDates() = drafts.filterValues { it.isNotEmpty() }.keys
        override fun keepDraft(date: LocalDate, text: String) { drafts[date] = text; draftVersion.value++ }
        fun external(date: LocalDate, text: String) = keepDraft(date, text) // what quick add does
        override fun addLines(date: LocalDate, input: String, onSaved: () -> Unit, onError: (String) -> Unit) {
            refuse?.let { onError(it); return }
            saved += date to input; drafts.remove(date); draftVersion.value++; onSaved()
        }
        override fun editLine(date: LocalDate, lineId: String, new: String, onSaved: () -> Unit, onError: (String) -> Unit) {}
        override fun deleteLine(date: LocalDate, lineId: String) {}
        override fun setMood(date: LocalDate, mood: Int?) {}
        override fun addPhoto(date: LocalDate, file: File) {}
        override fun deletePhoto(id: String) {}
        override suspend fun photo(id: String, localPath: String?): File? = null
    }

    @Test
    fun `text kept by quick add shows up in the open writer, and a saved draft clears it`() = runComposeUiTest {
        val ui = FakeUi()
        setContent { LoopDoTheme { Writer(day, day, LocalTime.NOON, null, ui, { null }) } }
        runOnIdle { ui.external(day, "met sam") }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("met sam")).fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { ui.drafts.remove(day); ui.draftVersion.value++ } // quick add saved it
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("met sam")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a refused save keeps the text and the draft`() = runComposeUiTest {
        val ui = FakeUi(refuse = "the day has ended.")
        setContent { LoopDoTheme { Writer(day, day, LocalTime.NOON, null, ui, { null }) } }
        onNode(hasSetTextAction()).performTextInput("keep me")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("the day has ended.").assertExists()
        assertEquals("keep me", ui.drafts[day])
        assertTrue(ui.saved.isEmpty())
    }

    @Test
    fun `after the day end the writer opens on the day its unsent line was started`() {
        val ui = FakeUi().apply { drafts[day] = "late thought" }
        assertEquals(day, writerDate(day.plusDays(1), ui))
        assertEquals(day.plusDays(1), writerDate(day.plusDays(1), FakeUi()))
        runComposeUiTest {
            setContent { LoopDoTheme { Writer(writerDate(day.plusDays(1), ui), day.plusDays(1), LocalTime.of(4, 1), null, ui, { null }) } }
            onNodeWithText("late thought").assertExists()
            onNodeWithText("unsaved from before the day ended", substring = true).assertExists()
        }
    }

    @Test
    fun `quick add opens only when the app itself would`() {
        assertTrue(quickAllowed(signedIn = true, blocked = null, hasSettings = true))
        assertFalse(quickAllowed(signedIn = false, blocked = null, hasSettings = true))
        assertFalse(quickAllowed(signedIn = true, blocked = "another account…", hasSettings = true))
        assertFalse(quickAllowed(signedIn = true, blocked = null, hasSettings = false))
    }

    @Test
    fun `a quick command while hidden opens quick add, not the main window`() {
        val shown = mutableStateOf(false); val quick = mutableStateOf(false)
        route(Command.Quick, shown, quick)
        assertTrue(quick.value); assertFalse(shown.value)
        route(Command.Background, shown, quick); assertFalse(shown.value)
        route(Command.Open, shown, quick); assertTrue(shown.value)
    }

    @Test
    fun `a photo that can't be made small enough is refused and leaves no file`() {
        val dir = Files.createTempDirectory("loopdo-big").toFile()
        val src = File(dir, "noise.png").apply { val img = BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB); val r = java.util.Random(1)
            for (x in 0 until 800) for (y in 0 until 800) img.setRGB(x, y, r.nextInt()); ImageIO.write(img, "png", this) }
        val out = File(dir, "out.webp")
        assertFailsWith<IllegalArgumentException> { compressPhoto(src, out, maxBytes = 10_000) }
        assertFalse(out.exists())
    }
}
