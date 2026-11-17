package app.loopdo.android.journal

import app.loopdo.android.sync.oneTransaction
import app.loopdo.android.time.TrustedClock
import app.loopdo.shared.Limits
import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.task.uuidV5
import com.powersync.PowerSyncDatabase
import com.powersync.db.internal.PowerSyncTransaction
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A compressed photo already in the app's files, ready to join a day. */
data class PreparedPhoto(val id: UUID, val path: String, val width: Int, val height: Int, val bytes: Int)

/**
 * The journal as a time log (M6). Every change is ONE local transaction. A line is stamped with the trusted
 * clock's wall time in the zone in force (shared settingsAt); edits and deletes match the exact stored line,
 * so a line added meanwhile (the widget) is never touched. Photos are rows first (offline-safe) and files
 * queued for upload (photo_files, local only).
 */
class JournalActions(
    private val db: PowerSyncDatabase,
    private val clock: TrustedClock,
    private val dateAt: suspend (Instant) -> LocalDate,
    private val settingsAt: suspend (Instant) -> DaySettings,
    private val userId: () -> UUID?,
) {
    private fun user(): UUID = userId() ?: throw IllegalStateException("not signed in")
    private fun entryId(date: LocalDate) = uuidV5(user(), "journal:$date").toString()

    /**
     * New lines for [date], each stamped. Only today, except an unsaved draft from an earlier day, saved
     * onto that day with the time it was last edited ([at]).
     */
    suspend fun addLines(date: LocalDate, input: String, at: Instant? = null) {
        val now = clock.now().instant
        val stampAt = at ?: now
        require(date == dateAt(now) || (at != null && dateAt(at) == date && date < dateAt(now))) { "new lines go on today." }
        val lines = stampLines(input, settingsAt(stampAt).let { stampAt.atZone(it.zone).toLocalTime() })
        require(lines.isNotEmpty()) { "type something first." }
        val id = entryId(date)
        db.oneTransaction { tx ->
            val old = tx.entryText(id)
            val new = (listOfNotNull(old?.trimEnd()?.takeIf { it.isNotEmpty() }) + lines).joinToString("\n")
            require(new.length <= MAX_JOURNAL) { "today's entry is full ($MAX_JOURNAL characters)." }
            tx.writeText(id, date, old, new)
        }
    }

    /** [old] (the line as stored) gets [newText]; its time stays. */
    suspend fun editLine(date: LocalDate, old: String, newText: String) {
        val text = newText.replace('\n', ' ').trim()
        require(text.isNotEmpty()) { "type something first, or delete the line." }
        val id = entryId(date)
        db.oneTransaction { tx ->
            val current = tx.entryText(id) ?: throw IllegalStateException("that line changed. try again.")
            val new = replaceLine(current, old, text) ?: throw IllegalStateException("that line changed. try again.")
            require(new.length <= MAX_JOURNAL) { "that day's entry is full ($MAX_JOURNAL characters)." }
            tx.writeText(id, date, current, new)
        }
    }

    suspend fun deleteLine(date: LocalDate, old: String) {
        val id = entryId(date)
        db.oneTransaction { tx ->
            val current = tx.entryText(id) ?: throw IllegalStateException("that line changed. try again.")
            val new = removeLine(current, old) ?: throw IllegalStateException("that line changed. try again.")
            tx.writeText(id, date, current, new)
        }
    }

    /** One mood per day (any day up to today; the journal stays editable). */
    suspend fun setMood(date: LocalDate, mood: Int?) {
        require(mood == null || mood in Limits.MOOD_RANGE) { "pick one of the five moods." }
        require(date <= dateAt(clock.now().instant)) { "that day hasn't started yet." }
        val id = entryId(date)
        db.oneTransaction { tx ->
            if (tx.entryText(id) == null) tx.insertEntry(id, date, "")
            tx.execute("UPDATE journal_entries SET mood = ? WHERE id = ?", listOf(mood, id))
        }
    }

    /**
     * Adds [photo] to [date] (≤ 4 a day). Today's photo carries its time; a photo added to an earlier day has
     * none (it shows last). The file waits in the queue until it reaches R2.
     */
    suspend fun addPhoto(date: LocalDate, photo: PreparedPhoto): UUID {
        val now = clock.now().instant
        val today = dateAt(now)
        require(date <= today) { "that day hasn't started yet." }
        val user = user()
        val id = entryId(date)
        db.oneTransaction { tx ->
            val count = tx.get("SELECT count(*) FROM journal_photos WHERE entry_id = ?", listOf(id)) { it.getLong(0)!! }
            require(count < MAX_PHOTOS) { "a day holds $MAX_PHOTOS photos." }
            if (tx.entryText(id) == null) tx.insertEntry(id, date, "")
            tx.execute(
                "INSERT INTO journal_photos (id, user_id, entry_id, r2_key, width, height, bytes, sort_order, added_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    photo.id.toString(), user.toString(), id, "u/$user/$id/${photo.id}.webp",
                    photo.width, photo.height, photo.bytes, count, now.toString().takeIf { date == today },
                ),
            )
            tx.execute("INSERT INTO photo_files (id, path, uploaded) VALUES (?, ?, 0)", listOf(photo.id.toString(), photo.path))
        }
        return photo.id
    }

    /** Removes the photo from its day and this phone. Its copy in R2 stays (the phone never deletes there). */
    suspend fun deletePhoto(id: UUID) {
        val path: String? = db.oneTransaction { tx ->
            val p = tx.getOptional("SELECT path FROM photo_files WHERE id = ?", listOf(id.toString())) { it.getString(0) ?: "" }
            tx.execute("DELETE FROM journal_photos WHERE id = ?", listOf(id.toString()))
            tx.execute("DELETE FROM photo_files WHERE id = ?", listOf(id.toString()))
            p
        }
        path?.takeIf { it.isNotEmpty() }?.let { runCatching { File(it).delete() } }
    }

    // --- inside a transaction ------------------------------------------------

    private fun PowerSyncTransaction.entryText(id: String): String? =
        getOptional("SELECT coalesce(text, '') FROM journal_entries WHERE id = ?", listOf(id)) { it.getString(0) ?: "" }

    private fun PowerSyncTransaction.insertEntry(id: String, date: LocalDate, text: String) = execute(
        "INSERT INTO journal_entries (id, user_id, loopdo_date, text) VALUES (?, ?, ?, ?)",
        listOf(id, user().toString(), date.toString(), text),
    )

    private fun PowerSyncTransaction.writeText(id: String, date: LocalDate, old: String?, new: String) {
        if (old == null) insertEntry(id, date, new) else execute("UPDATE journal_entries SET text = ? WHERE id = ?", listOf(new, id))
    }

    private companion object {
        const val MAX_JOURNAL = 20_000 // journal_entries.text check
        const val MAX_PHOTOS = 4 // journal_photos_limit trigger
    }
}
