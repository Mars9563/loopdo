package app.loopdo.android.journal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.android.sync.LoopDoSchema
import app.loopdo.android.time.AnchorStore
import app.loopdo.android.time.DeviceClocks
import app.loopdo.android.time.SignedAnchor
import app.loopdo.android.time.TrustedClock
import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.loopDoDate
import app.loopdo.shared.task.uuidV5
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import com.powersync.db.crud.CrudEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Runs on the phone: the journal as a time log (M6). LoopDo today = sat 14 nov; the clock reads 00:42 IST. */
@RunWith(AndroidJUnit4::class)
class JournalActionsTest {
    private lateinit var db: PowerSyncDatabase
    private lateinit var journal: JournalActions
    private val user = UUID.fromString("0b5c4f0e-6d1a-4c55-9d8e-3f0a1b2c3d4e")
    private var now = Instant.parse("2026-11-14T19:12:00Z") // 00:42 Sun IST = LoopDo sat 14 nov
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    private val today = LocalDate.of(2026, 11, 14)
    private val entryId = uuidV5(user, "journal:2026-11-14").toString()

    private val clocks = object : DeviceClocks {
        override fun wallClock() = now
        private var elapsed = 1_000_000L
        override fun elapsedRealtimeMs() = elapsed++
        override fun bootCount() = 3
        override fun networkTime() = now
    }
    private val anchors = object : AnchorStore {
        override fun current(): SignedAnchor? = null
        override fun save(a: SignedAnchor) = Unit
    }

    @Before
    fun open() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = PowerSyncDatabase(DatabaseDriverFactory(ctx), LoopDoSchema.schema, dbFilename = "test-${UUID.randomUUID()}.db")
        journal = JournalActions(db, TrustedClock(clocks, anchors), { loopDoDate(it, ist) }, { ist }, { user })
    }

    @After
    fun close() = runBlocking { db.close() }

    private suspend fun text(): String = db.get("SELECT text FROM journal_entries WHERE id = ?", listOf(entryId)) { it.getString(0)!! }
    private suspend fun ops(): List<CrudEntry> = db.getCrudBatch(1000)?.crud.orEmpty()
    private fun photo() = PreparedPhoto(
        UUID.randomUUID(),
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "p-${UUID.randomUUID()}.webp").apply { writeBytes(ByteArray(10)) }.path,
        2048, 1536, 10,
    )

    @Test
    fun addLinesStampsWithTheTrustedClock() = runBlocking {
        journal.addLines(today, "so i heard something funny today.\n\n  pizza was good ")
        assertEquals("[00:42] so i heard something funny today.\n[00:42] pizza was good", text())
        now = now.plusSeconds(47 * 60) // 01:29
        journal.addLines(today, "still up")
        assertEquals("[00:42] so i heard something funny today.\n[00:42] pizza was good\n[01:29] still up", text())
    }

    @Test
    fun editLineKeepsALineAddedMeanwhile() = runBlocking {
        journal.addLines(today, "had this for lunch")
        // The screen shows "[00:42] had this for lunch"; meanwhile the widget adds a line.
        now = now.plusSeconds(60)
        journal.addLines(today, "from the widget")
        journal.editLine(today, "[00:42] had this for lunch", "had dal for lunch")
        assertEquals("[00:42] had dal for lunch\n[00:43] from the widget", text())
        assertEquals("that line changed. try again.", runCatching { journal.editLine(today, "[00:42] had this for lunch", "x") }.exceptionOrNull()?.message)
    }

    @Test
    fun duplicateLinesChangeOneAtATime() = runBlocking {
        journal.addLines(today, "ok\nok")
        journal.deleteLine(today, "[00:42] ok")
        assertEquals("[00:42] ok", text())
        journal.editLine(today, "[00:42] ok", "okay")
        assertEquals("[00:42] okay", text())
    }

    @Test
    fun pastDayRefusesNewLines() = runBlocking {
        assertEquals("new lines go on today.", runCatching { journal.addLines(today.minusDays(1), "x") }.exceptionOrNull()?.message)
        // An unsaved draft from yesterday is saved onto yesterday with the time it was last edited.
        val lastEdit = Instant.parse("2026-11-13T17:00:00Z") // 22:30 IST fri 13 nov
        journal.addLines(today.minusDays(1), "draft from last night", at = lastEdit)
        val yesterday = uuidV5(user, "journal:2026-11-13").toString()
        assertEquals("[22:30] draft from last night", db.get("SELECT text FROM journal_entries WHERE id = ?", listOf(yesterday)) { it.getString(0)!! })
    }

    @Test
    fun moodIsOnePerDay() = runBlocking {
        journal.setMood(today, 4)
        journal.addLines(today, "fine day")
        journal.setMood(today, 5)
        assertEquals(5L, db.get("SELECT mood FROM journal_entries WHERE id = ?", listOf(entryId)) { it.getLong(0)!! })
        assertEquals("[00:42] fine day", text())
    }

    @Test
    fun fifthPhotoIsRefused() = runBlocking {
        repeat(4) { journal.addPhoto(today, photo()) }
        val before = ops().size
        assertEquals("a day holds 4 photos.", runCatching { journal.addPhoto(today, photo()) }.exceptionOrNull()?.message)
        assertEquals(before, ops().size)
        assertEquals(4L, db.get("SELECT count(*) FROM journal_photos") { it.getLong(0)!! })
    }

    @Test
    fun addPhotoIsOneTransactionWithItsKey() = runBlocking {
        val p = photo()
        val before = ops().size
        journal.addPhoto(today, p)
        val added = ops().drop(before)
        assertEquals(listOf("journal_entries", "journal_photos"), added.map { it.table }) // the entry first (foreign key)
        assertEquals(1, added.map { it.transactionId }.distinct().size)
        val row = db.get("SELECT r2_key, added_at, width, entry_id FROM journal_photos WHERE id = ?", listOf(p.id.toString())) {
            listOf(it.getString(0), it.getString(1), it.getLong(2).toString(), it.getString(3))
        }
        assertEquals("u/$user/$entryId/${p.id}.webp", row[0])
        assertEquals(now.toString(), row[1])
        assertEquals(listOf("2048", entryId), row.drop(2))
        assertEquals(0L, db.get("SELECT uploaded FROM photo_files WHERE id = ?", listOf(p.id.toString())) { it.getLong(0)!! })
        // Delete: the row, the queue entry and the phone's file go; nothing else.
        journal.deletePhoto(p.id)
        assertEquals(0L, db.get("SELECT count(*) FROM journal_photos") { it.getLong(0)!! })
        assertEquals(0L, db.get("SELECT count(*) FROM photo_files") { it.getLong(0)!! })
        assertFalse(File(p.path).exists())
        assertTrue(text().isEmpty())
    }
}
