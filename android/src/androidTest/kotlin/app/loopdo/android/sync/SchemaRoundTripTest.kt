package app.loopdo.android.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Runs on the phone: a real PowerSync database with LoopDo's schema. */
@RunWith(AndroidJUnit4::class)
class SchemaRoundTripTest {
    private lateinit var db: PowerSyncDatabase

    @Before
    fun open() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = PowerSyncDatabase(DatabaseDriverFactory(ctx), LoopDoSchema.schema, dbFilename = "test-${UUID.randomUUID()}.db")
    }

    @After
    fun close() = runBlocking { db.close() }

    @Test
    fun localWriteQueuesTheApiShape() = runBlocking {
        val id = UUID.randomUUID().toString()
        db.execute("INSERT INTO habits (id, user_id, title, kind, target) VALUES (?, ?, ?, ?, ?)", listOf(id, "u-1", "gym", "yes_no", 1))
        val tx = PowerSyncTransaction(db.getNextCrudTransaction()!!)
        val json = OpMapper.batch(tx)
        val op = (json["ops"] as kotlinx.serialization.json.JsonArray).single().jsonObject
        assertEquals("PUT", op["op"]!!.jsonPrimitive.content)
        assertEquals("habits", op["table"]!!.jsonPrimitive.content)
        assertEquals(id, op["id"]!!.jsonPrimitive.content)
        assertEquals("gym", op["data"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertNull(op["data"]!!.jsonObject["user_id"]) // never sent
        tx.complete()
        assertNull(db.getNextCrudTransaction())
    }

    @Test
    fun draftsAndUploadStateAreLocalOnly() = runBlocking {
        PowerSyncDraftStore(db).save("journal_entries.text", "pizza was good", "BAD_OP")
        val state = PowerSyncUploadState(db)
        state.markFailure(7, java.time.Instant.parse("2026-11-15T10:00:00Z"))
        assertEquals(java.time.Instant.parse("2026-11-15T10:00:00Z"), state.firstFailure(7))
        assertNull(db.getNextCrudTransaction()) // local-only tables never upload
        assertEquals(listOf("pizza was good"), db.getAll("SELECT payload FROM drafts") { it.getString(0)!! })
    }
}
