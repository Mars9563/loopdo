package app.loopdo.desktop

import app.loopdo.client.journal.LineDrafts
import app.loopdo.desktop.store.FilePopupDrafts
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** D3 exit: an unsent line typed on the laptop is still there after the app quits and starts again. */
class DraftSurvivesTest {
    @Test
    fun `a typed line survives a restart`() {
        val f = File(Files.createTempDirectory("loopdo-draft").toFile(), "drafts.json")
        val d = LocalDate.of(2026, 11, 19)
        LineDrafts(FilePopupDrafts(f)).keep(d, "half a thought", Instant.parse("2026-11-19T00:10:00Z"), earlierDay = false)
        assertEquals("half a thought", LineDrafts(FilePopupDrafts(f)).line(d).first) // a new process reading the same folder
    }
}
