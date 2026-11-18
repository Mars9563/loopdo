package app.loopdo.client.onboarding

import app.loopdo.client.config.AppConfig
import com.powersync.PowerSyncDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant
import java.util.UUID

class KtorSettingsApi(private val http: HttpClient, private val token: suspend () -> String?) : SettingsApi {
    override suspend fun setDaySettings(timeZone: String, dayEnd: String) {
        val t = token() ?: throw IOException("not signed in")
        val r = try {
            http.post("${AppConfig.apiUrl}/api/day-settings") {
                bearerAuth(t)
                contentType(ContentType.Application.Json)
                setBody("""{"timeZone":"$timeZone","dayEnd":"$dayEnd"}""")
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message, e)
        }
        when {
            r.status.value == 200 -> Unit
            r.status.value == 422 -> throw IllegalArgumentException("that time zone or day end was refused. pick another.")
            else -> throw IOException("server answered ${r.status.value}")
        }
    }
}

class PowerSyncProfileWriter(private val db: PowerSyncDatabase, private val userId: () -> UUID?) : ProfileWriter {
    override suspend fun finishOnboarding(displayName: String, at: Instant) {
        val id = userId() ?: throw IOException("not signed in")
        db.execute("UPDATE profiles SET display_name = ?, onboarded_at = ? WHERE id = ?", listOf(displayName, at.toString(), id.toString()))
    }
}

/** True once the phone holds the user's day settings (synced down after POST /api/day-settings). */
fun PowerSyncDatabase.hasDaySettings(): Flow<Boolean> =
    watch("SELECT count(*) FROM day_settings_versions") { it.getLong(0)!! }.map { it.firstOrNull() ?: 0L > 0 }

/** Rows on the phone, for the "restoring your data" counter. */
fun PowerSyncDatabase.localRowCount(): Flow<Long> = watch(
    """SELECT (SELECT count(*) FROM habits) + (SELECT count(*) FROM habit_logs) + (SELECT count(*) FROM tasks)
       + (SELECT count(*) FROM journal_entries) + (SELECT count(*) FROM lists) + (SELECT count(*) FROM habit_schedules)""",
) { it.getLong(0)!! }.map { it.firstOrNull() ?: 0L }

/** True once the profile says onboarding finished (the name step). */
fun PowerSyncDatabase.isOnboarded(): Flow<Boolean> =
    watch("SELECT count(*) FROM profiles WHERE onboarded_at IS NOT NULL") { it.getLong(0)!! }.map { it.firstOrNull() ?: 0L > 0 }
