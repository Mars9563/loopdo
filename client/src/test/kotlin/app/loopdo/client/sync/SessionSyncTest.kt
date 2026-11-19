package app.loopdo.client.sync

import app.loopdo.client.auth.AuthState
import app.loopdo.client.popup.PopupDrafts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account rule, once for both devices (it was copied between the phone and the laptop; ultrareview). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSyncTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private class FakeDb(var unsynced: Boolean = false) : SessionDb {
        val log = mutableListOf<String>()
        override suspend fun connect() { log += "connect" }
        override suspend fun disconnect() { log += "disconnect" }
        override suspend fun disconnectAndClear() { log += "clear" }
        override suspend fun hasUnsyncedWork() = unsynced
    }
    private class Last(var v: String? = null) : LastUserStore { override fun get() = v; override fun set(user: String) { v = user } }
    private class MapDrafts : PopupDrafts {
        val m = mutableMapOf<String, String>()
        override fun load(key: String) = m[key].orEmpty()
        override fun save(key: String, text: String) { if (text.isEmpty()) m.remove(key) else m[key] = text }
        override fun keys() = m.keys.toSet()
    }

    private fun signedIn(u: UUID) = AuthState.SignedIn(u, "x@y.z")

    @Test
    fun `the same account connects, sign-out only disconnects`() = runTest {
        val session = MutableStateFlow<AuthState>(AuthState.Loading)
        val db = FakeDb(); val last = Last(a.toString())
        val switched = mutableListOf<Pair<String, String>>()
        SessionSync(session, db, last, backgroundScope, "refused", onAccountSwitch = { f, t -> switched += f to t })
        session.value = signedIn(a); runCurrent()
        session.value = AuthState.SignedOut; runCurrent()
        assertEquals(listOf("connect", "disconnect"), db.log, "nothing cleared, nothing lost")
        assertTrue(switched.isEmpty())
    }

    @Test
    fun `another account clears the last one's data, sets its drafts aside, then connects`() = runTest {
        val session = MutableStateFlow<AuthState>(AuthState.Loading)
        val db = FakeDb(); val last = Last(a.toString())
        val switched = mutableListOf<Pair<String, String>>()
        val sync = SessionSync(session, db, last, backgroundScope, "refused", onAccountSwitch = { f, t -> switched += f to t })
        session.value = signedIn(b); runCurrent()
        assertEquals(listOf("clear", "connect"), db.log)
        assertEquals(listOf(a.toString() to b.toString()), switched)
        assertEquals(b.toString(), last.v)
        assertNull(sync.blocked.value)
    }

    @Test
    fun `another account is refused while the last one's changes haven't reached the server`() = runTest {
        val session = MutableStateFlow<AuthState>(AuthState.Loading)
        val db = FakeDb(unsynced = true); val last = Last(a.toString())
        var switched = false
        val sync = SessionSync(session, db, last, backgroundScope, "refused", onAccountSwitch = { _, _ -> switched = true })
        session.value = signedIn(b); runCurrent()
        assertEquals("refused", sync.blocked.value)
        assertTrue(db.log.isEmpty(), "nothing cleared, not connected")
        assertEquals(a.toString(), last.v, "still the last account")
        assertTrue(!switched, "its drafts stay where they are")
        session.value = AuthState.SignedOut; runCurrent()
        assertNull(sync.blocked.value, "signing out unblocks")
    }

    @Test
    fun `drafts move aside per account and come back`() {
        val current = MapDrafts()
        val aside = mutableMapOf<String, MapDrafts>()
        val asideOf = { u: String -> aside.getOrPut(u) { MapDrafts() } }
        current.save("journal:2026-11-19", "a's private line")
        switchDrafts(current, asideOf, "a", "b")
        assertTrue(current.keys().isEmpty(), "b sees nothing of a's")
        current.save("journal:2026-11-19", "b's line")
        switchDrafts(current, asideOf, "b", "a")
        assertEquals("a's private line", current.load("journal:2026-11-19"))
        assertTrue(aside.getValue("a").keys().isEmpty(), "taken back, not left twice")
        switchDrafts(current, asideOf, "a", "b")
        assertEquals("b's line", current.load("journal:2026-11-19"))
    }
}
