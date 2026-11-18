package app.loopdo.client.settings


import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The export ZIP (M9, design §3.2): journal as Markdown, photos, all data as JSON, a README. */
class ExportTest {
    private val d18 = LocalDate.of(2026, 11, 18)
    private val day = ExportDay(d18, mood = 4, lines = listOf("09:19" to "haha", "" to "an old untimed line"), photos = listOf("p1"))

    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().decodeToString() }
        }
        return out
    }

    @Test fun dayMarkdownFormat() {
        assertEquals(
            "# wed 18 nov 2026\n\nmood: 4/5\n\n[09:19] haha\nan old untimed line\n\n![](../photos/2026-11-18/p1.webp)\n",
            dayMarkdown(day),
        )
        assertEquals("# wed 18 nov 2026\n\n[09:19] haha\n", dayMarkdown(ExportDay(d18, null, listOf("09:19" to "haha"), emptyList())))
    }

    @Test fun zipHasJournalPhotosDataReadme() {
        val photo = File.createTempFile("photo-p1", ".webp").apply { writeText("WEBP"); deleteOnExit() }
        val buf = ByteArrayOutputStream()
        val r = writeExport(buf, listOf(day), { if (it == "p1") photo else null }, """{"habits":[]}""", d18)
        val e = entries(buf.toByteArray())
        assertEquals(setOf("journal/2026-11-18.md", "photos/2026-11-18/p1.webp", "data.json", "README.txt"), e.keys)
        assertEquals("WEBP", e["photos/2026-11-18/p1.webp"])
        assertEquals("""{"habits":[]}""", e["data.json"])
        assertTrue("journal/" in e.getValue("README.txt"))
        assertEquals(ExportResult(days = 1, photos = 1, missing = emptyList(), bytes = buf.size().toLong()), r)
    }

    @Test fun exportOfflineListsMissingPhotos() {
        val buf = ByteArrayOutputStream()
        val r = writeExport(buf, listOf(day), { null }, "{}", d18) // offline: the photo isn't on this phone
        val e = entries(buf.toByteArray())
        assertEquals(listOf("p1"), r.missing)
        assertTrue("photos/2026-11-18/p1.webp" !in e)
        assertTrue("1 photo wasn't on this phone" in e.getValue("README.txt"))
        assertEquals("saved: 1 day, 0 photos, ${formatBytes(r.bytes)} · 1 photo wasn't on this phone", exportResultLine(r))
    }
}
