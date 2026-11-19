package app.loopdo.desktop

import app.loopdo.desktop.shell.Autostart
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutostartTest {
    @Test
    fun `on writes the autostart entry, off removes it, a missing folder is made`() {
        val dir = File(Files.createTempDirectory("loopdo-auto").toFile(), "not/yet/there")
        val a = Autostart(dir, bin = "/home/x/.local/bin/loopdo")
        assertFalse(a.on)
        a.set(true)
        val f = File(dir, "loopdo.desktop")
        assertTrue(a.on)
        assertTrue("Exec=/home/x/.local/bin/loopdo --background" in f.readText(), f.readText())
        assertEquals(listOf("loopdo.desktop"), dir.list()!!.toList(), "no temp file left behind")
        a.set(true); assertTrue(a.on, "twice is fine")
        a.set(false)
        assertFalse(a.on); assertFalse(f.exists())
        a.set(false); assertFalse(a.on)
    }
}
