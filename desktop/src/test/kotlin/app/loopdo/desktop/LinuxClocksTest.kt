package app.loopdo.desktop

import app.loopdo.desktop.time.LinuxDeviceClocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxClocksTest {
    @Test
    fun `elapsed comes from the first uptime field in milliseconds`() {
        val c = LinuxDeviceClocks(uptime = { "12345.67 999.00\n" }, bootId = { "x" })
        assertEquals(12_345_670L, c.elapsedRealtimeMs())
    }

    @Test
    fun `boot number is stable per boot id, differs across boots, never negative`() {
        val a = LinuxDeviceClocks(uptime = { "1 1" }, bootId = { "5d2f9c1e-8b7a-4f3e-9c1d-2a6b8e4f0a11\n" })
        val a2 = LinuxDeviceClocks(uptime = { "1 1" }, bootId = { "5d2f9c1e-8b7a-4f3e-9c1d-2a6b8e4f0a11" })
        val b = LinuxDeviceClocks(uptime = { "1 1" }, bootId = { "0f8e2c44-1a2b-4c3d-8e9f-a0b1c2d3e4f5" })
        assertEquals(a.bootCount(), a2.bootCount())
        assertNotEquals(a.bootCount(), b.bootCount())
        assertTrue(a.bootCount() >= 0 && b.bootCount() >= 0)
    }

    @Test
    fun `no network clock on linux`() = assertNull(LinuxDeviceClocks(uptime = { "1 1" }, bootId = { "x" }).networkTime())

    @Test
    fun `the real proc files are readable`() {
        val c = LinuxDeviceClocks()
        assertTrue(c.elapsedRealtimeMs() > 0)
        assertTrue(c.bootCount() >= 0)
    }
}
