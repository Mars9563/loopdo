package app.loopdo.client.settings

import app.loopdo.client.store.KeyValueStore
import app.loopdo.client.sync.PENDING_PHOTOS_SQL
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// M9 (spec §3.1): "is my data safe?" — what hasn't reached the server yet. The only way to lose data now is a phone
// that dies with changes still waiting, so this is shown plainly (settings) and nudged (Today, after 10 minutes).

/** [oldest] = since when something has been waiting; [lastSaved] = the last time nothing was left to send. */
data class Waiting(val changes: Int, val photos: Int, val oldest: Instant?, val lastSaved: Instant?, val online: Boolean) {
    val total get() = changes + photos
}

private fun plural(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"

private fun ago(t: Instant, now: Instant): String {
    val d = Duration.between(t, now)
    return when {
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        else -> "${d.toDays()} d ago"
    }
}

/** The settings DATA line and its second line (locked settings-v1 A/B). */
fun dataLine(w: Waiting, now: Instant, zone: ZoneId): Pair<String, String?> {
    if (w.total == 0) return "all saved" to w.lastSaved?.let { ago(it, now) }
    val main = listOfNotNull(
        w.changes.takeIf { it > 0 }?.let { plural(it, "change") },
        w.photos.takeIf { it > 0 }?.let { plural(it, "photo") },
    ).joinToString(" + ") + " waiting"
    val second = if (w.online) "online · sending…" else {
        val saved = w.lastSaved?.let { "last saved " + it.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "not saved yet"
        "offline · $saved\nthey go up when you're online"
    }
    return main to second
}

/** Today's header mark (locked settings-v1 C): `↑n` once something has waited more than 10 minutes. */
fun todayMark(w: Waiting, now: Instant): String? =
    if (w.total > 0 && w.oldest != null && Duration.between(w.oldest, now) > Duration.ofMinutes(10)) "↑${w.total}" else null

/** The live [Waiting] of this phone: PowerSync's upload queue, photos not in R2, and when the queue last emptied. */
class DataSafety(private val db: PowerSyncDatabase, private val store: KeyValueStore, private val now: () -> Instant) {

    fun waiting(): Flow<Waiting> = combine(
        db.watch("SELECT (SELECT count(*) FROM ps_crud), ($PENDING_PHOTOS_SQL)") { (it.getLong(0) ?: 0L).toInt() to (it.getLong(1) ?: 0L).toInt() },
        db.currentStatus.asFlow(),
    ) { counts, status ->
        val (changes, photos) = counts.firstOrNull() ?: (0 to 0)
        val t = now()
        // "Waiting since" starts when the queue goes from empty to not; "last saved" when it empties again.
        if (changes + photos > 0) {
            if (store.getLong(SINCE) == null) store.putLong(SINCE, t.toEpochMilli())
        } else if (store.getLong(SINCE) != null) {
            store.putLong(SAVED, t.toEpochMilli())
            store.remove(SINCE)
        }
        val synced = status.lastSyncedAt?.let { Instant.ofEpochMilli(it.toEpochMilliseconds()) }
        val saved = listOfNotNull(synced, store.getLong(SAVED)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)).maxOrNull()
        Waiting(changes, photos, store.getLong(SINCE)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli), saved, status.connected)
    }

    private companion object {
        const val SINCE = "waiting_since"
        const val SAVED = "last_saved"
    }
}
