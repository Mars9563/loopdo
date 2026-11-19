package app.loopdo.client.presence

import app.loopdo.shared.remind.presenceId
import com.powersync.PowerSyncDatabase
import java.time.Instant
import java.util.UUID

/**
 * D7: this laptop's presence row. The first write inserts it (a PUT with its device, so the server can check the id),
 * later ones only move active_until (a PATCH). Uploaded through the normal queue like any other change.
 */
class PresenceActions(private val db: PowerSyncDatabase, private val userId: () -> UUID?, private val deviceId: UUID) {
    /** False when nothing was written (signed out). */
    suspend fun setActiveUntil(at: Instant): Boolean {
        val user = userId() ?: return false // signed out: nothing to say
        val id = presenceId(user, deviceId).toString()
        db.writeTransaction { tx ->
            val exists = tx.getOptional("SELECT 1 FROM device_presence WHERE id = ?", listOf(id)) { 1 } != null
            if (exists) tx.execute("UPDATE device_presence SET active_until = ? WHERE id = ?", listOf(at.toString(), id))
            else tx.execute(
                "INSERT INTO device_presence (id, user_id, device_id, active_until) VALUES (?, ?, ?, ?)",
                listOf(id, user.toString(), deviceId.toString(), at.toString()),
            )
        }
        return true
    }
}
