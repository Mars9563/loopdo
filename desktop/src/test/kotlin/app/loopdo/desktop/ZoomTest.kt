package app.loopdo.desktop

import app.loopdo.desktop.store.FileKeyValue
import app.loopdo.desktop.ui.UiZoom
import app.loopdo.desktop.ui.zoomFor
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ZoomTest {
    @Test
    fun `the default follows the screen's height`() {
        assertEquals(1f, zoomFor(1080.0))
        assertEquals(1.5f, zoomFor(1440.0), "the owner's 2560×1440 laptop at KDE 100%")
        assertEquals(1f, zoomFor(960.0), "the same panel at KDE 150% is already scaled")
        assertEquals(2f, zoomFor(2160.0))
    }

    @Test
    fun `a chosen zoom is kept, within bounds, and 0 goes back to the screen's`() {
        val f = File(Files.createTempDirectory("loopdo-zoom").toFile(), "ui.json")
        val z = UiZoom(FileKeyValue(f), auto = 1.5f)
        assertEquals(1.5f, z.value)
        z.set(1.625f)
        assertEquals(1.65f, z.value, "5% steps")
        assertEquals(1.65f, UiZoom(FileKeyValue(f), auto = 1.5f).value, "kept for next time")
        z.set(10f); assertEquals(UiZoom.MAX, z.value)
        z.set(1.5f)
        assertEquals(null, FileKeyValue(f).getString("zoom"), "back to the default: nothing stored, so a new screen picks its own")
    }
}
