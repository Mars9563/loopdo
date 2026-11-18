package app.loopdo.desktop

import app.loopdo.client.time.SignedAnchor
import app.loopdo.desktop.store.FileAnchorStore
import app.loopdo.desktop.store.FileKeyValue
import app.loopdo.shared.time.TimeAnchor
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileStoresTest {
    private val dir: File = Files.createTempDirectory("loopdo-stores").toFile()

    @Test
    fun `values survive a reload`() {
        val f = File(dir, "kv.json")
        FileKeyValue(f).apply { putLong("a", 42); putString("u", "user-1") }
        val again = FileKeyValue(f)
        assertEquals(42L, again.getLong("a"))
        assertEquals("user-1", again.getString("u"))
        again.remove("a")
        assertNull(FileKeyValue(f).getLong("a"))
    }

    @Test
    fun `a broken or half-written file reads as empty`() {
        val f = File(dir, "bad.json").apply { writeText("{\"a\": 4") }
        File(dir, "bad.json.tmp").writeText("{\"a\": 9}")
        assertNull(FileKeyValue(f).getLong("a"))
    }

    @Test
    fun `the anchor round-trips and garbage reads as none`() {
        val f = File(dir, "anchor.json")
        val a = SignedAnchor(TimeAnchor(Instant.parse("2026-11-19T01:00:00Z"), 123_456L, 777), "sig==")
        FileAnchorStore(f).save(a)
        assertEquals(a, FileAnchorStore(f).current())
        f.writeText("nope")
        assertNull(FileAnchorStore(f).current())
    }
}
