package app.loopdo.desktop.time

import app.loopdo.client.time.DeviceClocks
import java.io.File
import java.time.Instant

/**
 * The laptop's clocks for the tick evidence (spec M10 "Clock check on the laptop"):
 * - elapsed = CLOCK_BOOTTIME from /proc/uptime (keeps counting through suspend, like Android's elapsedRealtime);
 * - boot number = a stable non-negative hash of this boot's random boot_id (new every boot, like BOOT_COUNT);
 * - no network clock: the server's signed anchor does that job.
 */
class LinuxDeviceClocks(
    private val uptime: () -> String = { File("/proc/uptime").readText() },
    private val bootId: () -> String = { File("/proc/sys/kernel/random/boot_id").readText() },
) : DeviceClocks {
    private val boot by lazy { bootId().trim().hashCode() and 0x7fffffff }

    override fun wallClock(): Instant = Instant.now()
    override fun elapsedRealtimeMs(): Long = (uptime().trim().substringBefore(' ').toBigDecimal() * 1000.toBigDecimal()).toLong()
    override fun bootCount(): Int = boot
    override fun networkTime(): Instant? = null
}
