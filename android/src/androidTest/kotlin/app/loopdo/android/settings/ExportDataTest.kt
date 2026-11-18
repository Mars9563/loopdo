package app.loopdo.android.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.android.sync.LoopDoSchema
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Runs on the phone: what the export reads from a real PowerSync database (M9). */
@RunWith(AndroidJUnit4::class)
class ExportDataTest {
    private lateinit var db: PowerSyncDatabase

    @Before
    fun open() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = PowerSyncDatabase(DatabaseDriverFactory(ctx), LoopDoSchema.schema, dbFilename = "test-${UUID.randomUUID()}.db")
        db.execute("INSERT INTO day_settings_versions (id, effective_at, time_zone, day_end) VALUES (?, '1970-01-01T00:00:00Z', 'Asia/Kolkata', '04:00')", listOf(UUID.randomUUID().toString()))
        Unit
    }

    @After
    fun close() = runBlocking { db.close(); app.loopdo.android.deleteTestDatabases() }

    @Test
    fun dataJsonHasEveryTableAndDaysReadLines() = runBlocking {
        val entry = UUID.randomUUID().toString()
        db.execute("INSERT INTO habits (id, title, kind, target, sort_order) VALUES (?, 'gym', 'yes_no', 1, 0)", listOf(UUID.randomUUID().toString()))
        db.execute("INSERT INTO journal_entries (id, loopdo_date, text, mood) VALUES (?, '2026-11-18', '', 4)", listOf(entry))
        db.execute("INSERT INTO journal_lines (id, entry_id, stamped_at, text, edited_at, sort_order) VALUES (?, ?, '2026-11-18T03:49:00Z', 'haha', '2026-11-18T03:49:00Z', 0)", listOf(UUID.randomUUID().toString(), entry))
        db.execute("INSERT INTO journal_lines (id, entry_id, stamped_at, text, edited_at, deleted_at, sort_order) VALUES (?, ?, '2026-11-18T03:50:00Z', 'gone', '2026-11-18T03:50:00Z', '2026-11-18T04:00:00Z', 1)", listOf(UUID.randomUUID().toString(), entry))
        val data = ExportData(db) { _, _ -> null }
        val json = Json.parseToJsonElement(data.dataJson()).jsonObject
        assertEquals(EXPORT_TABLES.toSet(), json.keys)
        assertEquals("gym", json.getValue("habits").jsonArray.single().jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(listOf("haha"), json.getValue("journal_lines").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }) // deleted left out
        val days = data.days()
        assertEquals(listOf(ExportDay(LocalDate.of(2026, 11, 18), 4, listOf("09:19" to "haha"), emptyList())), days)
        assertTrue(json.getValue("journal_entries").jsonArray.single().jsonObject.containsKey("id"))
    }
}
