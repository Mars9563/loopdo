package app.loopdo.android.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpMapperTest {
    @Test
    fun jsonColumnsAreSentAsJson() {
        // PowerSync stores evidence as JSON text; the API needs a JSON object (else INVALID_EVIDENCE).
        val op = PendingOp("PUT", "habit_logs", "id-1", mapOf(
            "habit_id" to "h-1", "loopdo_date" to "2026-11-15", "count" to 1L,
            "evidence" to """{"claimedDate":"2026-11-15","elapsedAtTickMs":2000}""",
        ))
        val data = OpMapper.toJson(op).getValue("data").jsonObject
        assertIs<JsonObject>(data["evidence"])
        assertEquals("2026-11-15", data["evidence"]!!.jsonObject["claimedDate"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(1L), data["count"])
    }

    @Test
    fun weekdaysAreSentAsArray() {
        val op = PendingOp("PUT", "habit_schedules", "s-1", mapOf("weekdays" to "[1,3,5]", "recurrence" to "weekdays"))
        assertIs<JsonArray>(OpMapper.toJson(op).getValue("data").jsonObject["weekdays"])
    }

    @Test
    fun deleteHasNoData() {
        val json = OpMapper.toJson(PendingOp("DELETE", "lists", "l-1", null))
        assertEquals(setOf("op", "table", "id"), json.keys)
    }

    @Test
    fun nullsStayNull() {
        val data = OpMapper.toJson(PendingOp("PATCH", "tasks", "t-1", mapOf("completed_at" to null))).getValue("data").jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, data["completed_at"])
    }

    @Test
    fun serverOwnedColumnsAreNotSent() {
        // Rows synced down carry these; the API refuses them (BAD_OP), and user_id always comes from the token.
        val op = PendingOp("PATCH", "habits", "h-1", mapOf(
            "title" to "gym", "user_id" to "u-1", "created_at" to "x", "updated_at" to "y", "verified" to 1L, "completed_on" to "2026-11-15",
        ))
        assertEquals(setOf("title"), OpMapper.toJson(op).getValue("data").jsonObject.keys)
    }

    @Test
    fun previousValuesAndTheRetryKeyAreSent() {
        // M6.5: the server applies count differences (new - old) and needs a key that is the same on every retry.
        val op = PendingOp("PATCH", "habit_logs", "l-1", mapOf("count" to 4L), old = mapOf("count" to 3L), key = "k-1")
        val json = OpMapper.toJson(op)
        assertEquals(JsonPrimitive(3L), json.getValue("old").jsonObject["count"])
        assertEquals("k-1", json.getValue("key").jsonPrimitive.content)
    }

    @Test
    fun noOldOrKeyWhenThereAreNone() {
        val json = OpMapper.toJson(PendingOp("PATCH", "tasks", "t-1", mapOf("title" to "x")))
        assertEquals(false, "old" in json)
        assertEquals(false, "key" in json)
    }
}
