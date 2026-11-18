package app.loopdo.android.today

import app.loopdo.android.time.AnchorStore
import app.loopdo.android.time.DeviceClocks
import app.loopdo.android.time.EvidenceFactory
import app.loopdo.android.time.SignedAnchor
import app.loopdo.android.time.TrustedClock
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** D1: the forged tick (exit-check tool) is refused unless the app says it's a debug build. */
class ActionsDebugFlagTest {
    private val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PowerSyncDatabase::class.java)) { _, m, _ ->
        error("db must not be touched: ${m.name}")
    } as PowerSyncDatabase
    private val clocks = object : DeviceClocks {
        override fun wallClock(): Instant = Instant.EPOCH
        override fun elapsedRealtimeMs() = 0L
        override fun bootCount() = 0
        override fun networkTime(): Instant? = null
    }
    private val anchors = object : AnchorStore {
        override fun current(): SignedAnchor? = null
        override fun save(a: SignedAnchor) {}
    }

    @Test
    fun `forged tick is refused outside debug builds`() = runTest {
        val actions = Actions(db, TrustedClock(clocks, anchors), EvidenceFactory(clocks, anchors), { LocalDate.EPOCH }, { null }, debug = false)
        val e = assertFailsWith<IllegalStateException> { actions.debugForgedTick(UUID.randomUUID(), null) }
        assertEquals("debug only", e.message)
    }
}
