package app.loopdo.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class PathsTest {
    @Test
    fun `the data folder can be overridden`() = assertEquals(File("/x"), dataDir(mapOf("LOOPDO_DATA_DIR" to "/x"), home = "/h"))

    @Test
    fun `by default it lives in the XDG data home`() {
        assertEquals(File("/h/.local/share/loopdo"), dataDir(emptyMap(), home = "/h"))
        assertEquals(File("/d/loopdo"), dataDir(mapOf("XDG_DATA_HOME" to "/d"), home = "/h"))
    }
}
