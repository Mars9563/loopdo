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

/** Runs on the phone: the journal as a time log (M6; M6.5: a line is a row). LoopDo today = sat 14 nov; the clock reads 00:42 IST. */
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

    private suspend fun text(): String = text(entryId)

    /** The day's live lines as shown ("[00:42] text"), in save order. */
    private suspend fun text(entry: String): String = db.getAll(
        "SELECT stamped_at, text FROM journal_lines WHERE entry_id = ? AND deleted_at IS NULL ORDER BY stamped_at, sort_order", listOf(entry),
    ) { shown(it.getString(0)?.let { t -> Instant.parse(t).atZone(ist.zone).toLocalTime() }, it.getString(1)!!) }.joinToString("\n")

    /** The id of the first live line reading [text] (its text, without the stamp). */
    private suspend fun lineId(text: String, entry: String = entryId): String = db.get(
        "SELECT id FROM journal_lines WHERE entry_id = ? AND text = ? AND deleted_at IS NULL ORDER BY sort_order LIMIT 1", listOf(entry, text),
    ) { it.getString(0)!! }
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
        val lunch = lineId("had this for lunch")
        journal.editLine(today, lunch, "had dal for lunch")
        assertEquals("[00:42] had dal for lunch\n[00:43] from the widget", text())
        journal.deleteLine(today, lunch)
        assertEquals("that line changed. try again.", runCatching { journal.editLine(today, lunch, "x") }.exceptionOrNull()?.message)
    }

    @Test
    fun duplicateLinesChangeOneAtATime() = runBlocking {
        journal.addLines(today, "ok\nok")
        journal.deleteLine(today, lineId("ok"))
        assertEquals("[00:42] ok", text())
        journal.editLine(today, lineId("ok"), "okay")
        assertEquals("[00:42] okay", text())
    }

    @Test
    fun pastDayRefusesNewLines() = runBlocking {
        assertEquals("new lines go on today.", runCatching { journal.addLines(today.minusDays(1), "x") }.exceptionOrNull()?.message)
        // An unsaved draft from yesterday is saved onto yesterday with the time it was last edited.
        val lastEdit = Instant.parse("2026-11-13T17:00:00Z") // 22:30 IST fri 13 nov
        journal.addLines(today.minusDays(1), "draft from last night", at = lastEdit)
        val yesterday = uuidV5(user, "journal:2026-11-13").toString()
        assertEquals("[22:30] draft from last night", text(yesterday))
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
        assertEquals(listOf("journal_entries", "journal_photos"), added.map { it.table }) // the day first (foreign key)
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

    // Review of M6, critical 1: an earlier day's draft is never stuck; outside its day it gets that day's end.
    @Test
    fun earlierDayDraftOutsideItsDayIsStampedAtTheDayEnd() = runBlocking {
        journal.addLines(today.minusDays(1), "fixed the next morning", at = now) // edited today
        journal.addLines(today.minusDays(1), "from before the stamps", at = Instant.EPOCH) // no time known
        val yesterday = uuidV5(user, "journal:2026-11-13").toString()
        assertEquals("[03:59] fixed the next morning\n[03:59] from before the stamps", text(yesterday))
    }

    // Review of M6, critical 2: a closed day that counted keeps at least one line or photo.
    @Test
    fun pastDayKeepsItsLastLineOrPhoto() = runBlocking {
        val yesterday = uuidV5(user, "journal:2026-11-13").toString()
        db.execute("INSERT INTO journal_entries (id, user_id, loopdo_date, text) VALUES (?, ?, '2026-11-13', ?)", listOf(yesterday, user.toString(), "[19:05] long day\n[23:30] 2 applications sent"))
        journal.moveOldText()
        journal.deleteLine(today.minusDays(1), lineId("long day", yesterday))
        assertEquals("a past day keeps at least one line or photo. edit it instead.", runCatching { journal.deleteLine(today.minusDays(1), lineId("2 applications sent", yesterday)) }.exceptionOrNull()?.message)
        // A photo-only past day keeps its photo; today can be emptied.
        val p = photo()
        journal.addPhoto(today, p)
        journal.deletePhoto(p.id)
        val q = UUID.randomUUID()
        val older = uuidV5(user, "journal:2026-11-12").toString()
        db.execute("INSERT INTO journal_entries (id, user_id, loopdo_date, text) VALUES (?, ?, '2026-11-12', '')", listOf(older, user.toString()))
        db.execute("INSERT INTO journal_photos (id, user_id, entry_id, r2_key, sort_order) VALUES (?, ?, ?, ?, 0)", listOf(q.toString(), user.toString(), older, "u/$user/$older/$q.webp"))
        assertEquals("a past day keeps at least one line or photo. edit it instead.", runCatching { journal.deletePhoto(q) }.exceptionOrNull()?.message)
        assertEquals(1L, db.get("SELECT count(*) FROM journal_photos WHERE id = ?", listOf(q.toString())) { it.getLong(0)!! })
    }

    // M6.5: two devices. A line is a row, a delete keeps it (marked), an edit carries the edit it replaced.
    @Test
    fun editAndDeleteCarryTheEditTheyReplaced() = runBlocking {
        journal.addLines(today, "first")
        val id = lineId("first")
        now = now.plusSeconds(60)
        journal.editLine(today, id, "second")
        journal.deleteLine(today, lineId("second"))
        val lineOps = ops().filter { it.table == "journal_lines" }
        assertEquals(listOf("PUT", "PATCH", "PATCH"), lineOps.map { it.op.toJson() })
        assertEquals("2026-11-14T19:12:00Z", lineOps[1].previousValues!!["edited_at"]) // the edit the laptop could have changed
        assertEquals("2026-11-14T19:13:00Z", lineOps[2].previousValues!!["edited_at"])
        assertEquals(1L, db.get("SELECT count(*) FROM journal_lines WHERE id = ? AND deleted_at IS NOT NULL", listOf(id)) { it.getLong(0)!! })
    }

    @Test
    fun oldTextMovesIntoLinesOnce() = runBlocking {
        db.execute(
            "INSERT INTO journal_entries (id, user_id, loopdo_date, text, mood) VALUES (?, ?, '2026-11-14', ?, 4)",
            listOf(entryId, user.toString(), "written before times\n[23:50] late night\n[00:20] still up"),
        )
        journal.moveOldText()
        journal.moveOldText() // nothing left to move
        assertEquals("written before times\n[23:50] late night\n[00:20] still up", text())
        assertEquals("", db.get("SELECT text FROM journal_entries WHERE id = ?", listOf(entryId)) { it.getString(0)!! })
        assertEquals(4L, db.get("SELECT mood FROM journal_entries WHERE id = ?", listOf(entryId)) { it.getLong(0)!! })
        // 00:20 is after midnight, still sat 14 nov's LoopDo day (day end 04:00): sun 15 nov 00:20 IST.
        assertEquals("2026-11-14T18:50:00Z", db.get("SELECT stamped_at FROM journal_lines WHERE text = 'still up'") { it.getString(0)!! })
        // The ids come from the day and the line's place: a second device makes the same rows.
        assertEquals(uuidV5(UUID.fromString(entryId), "line:0").toString(), lineId("written before times"))
    }
}
