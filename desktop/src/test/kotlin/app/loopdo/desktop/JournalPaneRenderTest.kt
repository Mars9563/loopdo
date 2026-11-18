package app.loopdo.desktop

import app.loopdo.client.journal.DayView
import app.loopdo.client.journal.JournalModel
import app.loopdo.client.journal.LogBlock
import app.loopdo.client.journal.LogItem
import app.loopdo.client.journal.LogLine
import app.loopdo.desktop.ui.JournalPane
import app.loopdo.desktop.ui.JournalUi
import app.loopdo.desktop.ui.Place
import app.loopdo.desktop.ui.Shell
import app.loopdo.desktop.ui.savedStatus
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertTrue

/** D3: the journal screen renders the locked desktop-v1 B with a sample month (PNG kept for a look). */
class JournalPaneRenderTest {
    private fun day(d: Int, mood: Int, vararg lines: Pair<String, String>) = DayView(
        LocalDate.of(2026, 11, d), mood,
        listOf(LogBlock(lines.mapIndexed { i, (t, x) -> LogItem.Line(i, LogLine(LocalTime.parse(t), x), "", "l$d$i") })),
        emptyMap(), "", listOf("😞", "😕", "😐", "🙂", "😄")[mood - 1], if (d == 18) "today" else "",
    )

    object FakeUi : JournalUi {
        override val draftVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
        override fun draftDates(): Set<LocalDate> = emptySet()
        override fun draftFor(date: LocalDate) = ""
        override fun keepDraft(date: LocalDate, text: String) {}
        override fun addLines(date: LocalDate, input: String, onSaved: () -> Unit, onError: (String) -> Unit) {}
        override fun editLine(date: LocalDate, lineId: String, new: String, onSaved: () -> Unit, onError: (String) -> Unit) {}
        override fun deleteLine(date: LocalDate, lineId: String) {}
        override fun setMood(date: LocalDate, mood: Int?) {}
        override fun addPhoto(date: LocalDate, file: File) {}
        override fun deletePhoto(id: String) {}
        override suspend fun photo(id: String, localPath: String?): File? = null
    }

    @Test
    fun `journal renders with a sample month`() {
        val today = day(18, 4, "09:19" to "slow start, good coffee.", "13:02" to "finished the phone tests. nothing lost, not even after a reinstall.")
        val feed = listOf(today, day(17, 3, "21:40" to "long day at college. tired, but the notifications work.", "23:05" to "tomorrow: stats."), day(16, 5, "07:30" to "gym before class, first time in weeks."))
        val moods = (4..18).associate { LocalDate.of(2026, 11, it) to listOf(2, 3, 2, 4, 3, 2, 5, 4, 3, 5, 3, 4, 5, 3, 4)[it - 4] }
        val m = JournalModel(today, feed, emptyList(), 12, LocalTime.of(20, 16), moods)
        val out = File("build/renders/journal.png")
        selfCheck(out) { Shell(Place.JOURNAL, {}, savedStatus("all saved · 1 min ago")) { JournalPane(m, FakeUi) { null } } }
        assertTrue(out.length() > 10_000)
    }
}
