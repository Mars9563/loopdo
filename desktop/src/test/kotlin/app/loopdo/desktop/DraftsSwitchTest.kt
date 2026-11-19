package app.loopdo.desktop

import app.loopdo.desktop.store.FilePopupDrafts
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ultrareview: one account's unsaved journal text must never be shown to, or saved under, another account. */
class DraftsSwitchTest {
    @Test
    fun `an account switch sets the unsaved text aside and gives it back when that account returns`() {
        val dir = Files.createTempDirectory("loopdo-drafts").toFile()
        val drafts = FilePopupDrafts(File(dir, "drafts.json"))
        drafts.save("journal:2026-11-19", "a private thought")
        drafts.save("journal:2026-11-19:at", "2026-11-19T15:00:00Z")
        val before = drafts.version.value

        drafts.switchAccount("user-a", "user-b")
        assertTrue(drafts.keys().isEmpty(), "account B sees nothing of A's")
        assertTrue(FilePopupDrafts(File(dir, "drafts.json")).keys().isEmpty(), "not on disk either")
        assertTrue(drafts.version.value > before, "an open writer re-reads")
        drafts.save("journal:2026-11-19", "b's own line")

        drafts.switchAccount("user-b", "user-a")
        assertEquals("a private thought", drafts.load("journal:2026-11-19"), "A's text is back, unchanged")
        assertEquals("2026-11-19T15:00:00Z", drafts.load("journal:2026-11-19:at"), "with its time")

        drafts.switchAccount("user-a", "user-b")
        assertEquals("b's own line", drafts.load("journal:2026-11-19"))
    }
}
