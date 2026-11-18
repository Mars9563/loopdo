package app.loopdo.desktop

import app.loopdo.client.auth.AuthBackend
import app.loopdo.client.auth.AuthRepository
import app.loopdo.client.auth.BackendStatus
import app.loopdo.client.sync.hasUnsyncedWork
import app.loopdo.desktop.store.FileKeyValue
import app.loopdo.desktop.sync.DesktopSync
import app.loopdo.desktop.sync.openDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeBackend : AuthBackend {
    override val status = MutableStateFlow<BackendStatus>(BackendStatus.SignedOut)
    override suspend fun signInWithPassword(email: String, password: String) {}
    override suspend fun signUp(email: String, password: String) {}
    override suspend fun verifySignupCode(email: String, code: String) {}
    override suspend fun resendSignupCode(email: String) {}
    override suspend fun sendPasswordReset(email: String) {}
    override suspend fun resetPasswordWithCode(email: String, code: String, newPassword: String) {}
    override suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String) {}
    override suspend fun signOut() { status.value = BackendStatus.SignedOut }
    override suspend fun refreshSession(): String? = null
}

class DesktopSyncTest {
    private val dir: File = Files.createTempDirectory("loopdo-sync").toFile()

    @Test
    fun `the laptop database uses the phone schema`() = runBlocking {
        val db = openDatabase(dir)
        val id = UUID.randomUUID().toString()
        db.execute("INSERT INTO habits (id, user_id, title, kind) VALUES (?, ?, ?, ?)", listOf(id, UUID.randomUUID().toString(), "read", "yes_no"))
        assertEquals("read", db.get("SELECT title FROM habits WHERE id = ?", listOf(id)) { it.getString(0)!! })
        assertTrue(hasUnsyncedWork(db), "a local write is queued for upload")
        db.close()
    }

    @Test
    fun `another account signing in over unsynced changes is refused`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = openDatabase(dir)
        db.execute("INSERT INTO lists (id, user_id, name) VALUES (?, ?, ?)", listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "work"))
        val previous = UUID.randomUUID()
        FileKeyValue(File(dir, "sync.json")).putString("last_user_id", previous.toString())
        val backend = FakeBackend()
        val sync = DesktopSync(dir, AuthRepository(backend, scope), scope, anchors = null, db = db)
        backend.status.value = BackendStatus.SignedIn(UUID.randomUUID(), "token")
        val blocked = withTimeout(10_000) { sync.blocked.first { it != null } }
        assertTrue(blocked!!.startsWith("this laptop still has changes from another account"))
        scope.cancel(); db.close()
    }
}
