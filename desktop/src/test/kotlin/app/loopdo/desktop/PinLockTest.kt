package app.loopdo.desktop

import app.loopdo.desktop.lock.PinLock
import app.loopdo.desktop.lock.Unlock
import app.loopdo.desktop.store.FileKeyValue
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Owner, 2026-11-19: a 4-digit pin that asks again after an hour away (desktop-pin-v1). */
class PinLockTest {
    private var now = Instant.parse("2026-11-19T08:00:00Z")
    private val file = File(Files.createTempDirectory("loopdo-pin").toFile(), "lock.json")
    private fun lock() = PinLock(FileKeyValue(file)) { now }

    @Test
    fun `a pin is stored hashed, never as digits`() {
        lock().set("4821")
        assertFalse("4821" in file.readText(), file.readText())
        assertTrue(lock().check("4821")); assertFalse(lock().check("4820"))
    }

    @Test
    fun `opening within the hour stays open, after an hour asks`() {
        val l = lock(); l.set("4821")
        assertFalse(l.needed.value, "just set: open")
        now = now.plus(Duration.ofMinutes(59)); l.windowOpened()
        assertFalse(l.needed.value)
        now = now.plus(Duration.ofMinutes(2)); l.windowOpened()
        assertTrue(l.needed.value)
        assertIs<Unlock.Ok>(l.tryUnlock("4821"))
        assertFalse(l.needed.value)
        val restarted = lock() // loopdo_ started again later
        now = now.plus(Duration.ofHours(2)); restarted.windowOpened()
        assertTrue(restarted.needed.value, "an hour since the last unlock, across a restart")
    }

    @Test
    fun `five wrong tries wait 30 s, and the wait survives a restart`() {
        val l = lock(); l.set("4821"); now = now.plus(Duration.ofHours(2)); l.windowOpened()
        repeat(4) { assertEquals(Unlock.Wrong(left = 4 - it), l.tryUnlock("0000")) }
        assertEquals(Unlock.Wait(now.plusSeconds(30)), l.tryUnlock("0000"))
        assertIs<Unlock.Wait>(l.tryUnlock("4821"), "even the right pin waits")
        assertIs<Unlock.Wait>(lock().tryUnlock("4821"), "killing loopdo_ doesn't reset the wait")
        now = now.plusSeconds(31)
        assertIs<Unlock.Ok>(lock().tryUnlock("4821"))
        assertEquals(Unlock.Wrong(left = 4), lock().tryUnlock("1111"), "a right pin starts the count again")
    }

    @Test
    fun `only 4 digits are a pin`() {
        listOf("123", "12345", "12a4", "").forEach { assertFailsWith<IllegalArgumentException>(it) { lock().set(it) } }
    }

    @Test
    fun `clear removes it`() {
        val l = lock(); l.set("4821"); now = now.plus(Duration.ofHours(2)); l.windowOpened()
        l.clear()
        assertFalse(l.isSet); assertFalse(l.needed.value)
        l.windowOpened(); assertFalse(l.needed.value, "no pin, never locked")
    }
}
