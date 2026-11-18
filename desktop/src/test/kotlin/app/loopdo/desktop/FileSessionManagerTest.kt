package app.loopdo.desktop

import app.loopdo.desktop.auth.FileSessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSessionManagerTest {
    private val dir: File = Files.createTempDirectory("loopdo-session").toFile()

    @Test
    fun `a saved session loads back and deletes`() = runBlocking {
        val f = File(dir, "session.json")
        val s = UserSession(accessToken = "a.b.c", refreshToken = "r", expiresIn = 3600, tokenType = "bearer", user = null)
        FileSessionManager(f).saveSession(s)
        assertEquals(s.accessToken, FileSessionManager(f).loadSessionOrNull()?.accessToken)
        assertEquals("r", FileSessionManager(f).loadSessionOrNull()?.refreshToken)
        FileSessionManager(f).deleteSession()
        assertNull(FileSessionManager(f).loadSessionOrNull())
    }

    @Test
    fun `a broken session file means signed out, not a crash`() = runBlocking {
        val f = File(dir, "broken.json").apply { writeText("{\"access_token\": ") }
        assertNull(FileSessionManager(f).loadSessionOrNull())
    }
}
