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
) : JournalCommands {
    private fun user(): UUID = userId() ?: throw IllegalStateException("not signed in")
    private fun entryId(date: LocalDate) = uuidV5(user(), "journal:$date").toString()

    /**
     * New lines for [date], each stamped. Only today, except an unsaved draft from an earlier day, saved
     * onto that day with the time it was last edited ([at]).
     */
    override suspend fun addLines(date: LocalDate, input: String, at: Instant?) {
        val now = clock.now().instant
        val today = dateAt(now)
        // Only today; an earlier day only for its unsaved draft (given with [at]), never stuck: a draft time outside
        // its day (edited the next morning, or none known before M6) is stamped one minute before that day's end.
        // The day just ended also takes a line with no time given: a pop-up opened before the day end and saved
        // after it saves onto the day it was opened for, at that day's end (owner, 2026-11-18).
        require(date == today || (date < today && (at != null || date == today.minusDays(1)))) { "new lines go on today." }
        val stampAt = when {
            date == today -> at ?: now
            at != null && dateAt(at) == date -> at
            else -> app.loopdo.shared.day.dayEndInstant(date, settingsAt(now)).minusSeconds(60)
        }
        val lines = splitInput(input)
        require(lines.isNotEmpty()) { "type something first." }
        require(lines.all { it.length <= MAX_LINE }) { "that line is too long ($MAX_LINE characters)." }
        val id = entryId(date)
        db.oneTransaction { tx ->
            if (tx.entryText(id) == null) tx.insertEntry(id, date, "")
            var order = tx.get("SELECT coalesce(max(sort_order), -1) FROM journal_lines WHERE entry_id = ?", listOf(id)) { it.getLong(0) ?: -1L }
            // M6.5: one row per line, so a line written on another device at the same time is never overwritten.
            for (text in lines) {
                tx.execute(
                    "INSERT INTO journal_lines (id, user_id, entry_id, stamped_at, text, edited_at, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    listOf(UUID.randomUUID().toString(), user().toString(), id, stampAt.toString(), text, now.toString(), ++order),
                )
            }
        }
    }

    /** The line [lineId] of [date] gets [newText]; its time stays. The newest edit wins between devices (server). */
    override suspend fun editLine(date: LocalDate, lineId: String, newText: String) {
        val text = newText.replace('\n', ' ').trim()
        require(text.isNotEmpty()) { "type something first, or delete the line." }
        require(text.length <= MAX_LINE) { "that line is too long ($MAX_LINE characters)." }
        val now = clock.now().instant
        val id = entryId(date)
        db.oneTransaction { tx ->
            check(tx.liveLine(lineId, id)) { CHANGED }
            tx.execute("UPDATE journal_lines SET text = ?, edited_at = ? WHERE id = ?", listOf(text, now.toString(), lineId))
        }
    }

    /** Deletes the line (kept as deleted, so an edit made meanwhile on another device still wins). */
    override suspend fun deleteLine(date: LocalDate, lineId: String) {
        val now = clock.now().instant
        val id = entryId(date)
        val closed = date < dateAt(now)
        db.oneTransaction { tx ->
            check(tx.liveLine(lineId, id)) { CHANGED }
            // A closed day that counted keeps at least one line or photo (else its journal day and streak change).
            check(!closed || tx.liveLineCount(id) > 1 || tx.photoCount(id) > 0) { KEEP_ONE }
            tx.execute("UPDATE journal_lines SET deleted_at = ? WHERE id = ?", listOf(now.toString(), lineId))
        }
    }

    /**
     * M6.5: the text of days saved before lines were rows becomes rows, once (ids from the day and the line's
     * place, so a second device doing the same makes the same rows). Each day is one transaction; nothing is lost:
     * the text is emptied only together with its rows being written.
     */
    suspend fun moveOldText() {
        val days = db.getAll("SELECT id, user_id, loopdo_date, text FROM journal_entries WHERE trim(coalesce(text, '')) <> ''") {
            listOf(it.getString(0)!!, it.getString(1).orEmpty(), it.getString(2)!!.take(10), it.getString(3)!!)
        }
        val now = clock.now().instant
        for ((entry, owner, day, text) in days) {
            val date = LocalDate.parse(day)
            val s = settingsAt(date.atTime(12, 0).atZone(java.time.ZoneOffset.UTC).toInstant())
            db.oneTransaction { tx ->
                val current = tx.entryText(entry) ?: return@oneTransaction
                if (current.isBlank()) return@oneTransaction
                parseLines(current).forEachIndexed { n, line ->
                    val lineId = uuidV5(UUID.fromString(entry), "line:$n").toString()
                    val at = line.time?.let { t -> (if (t < s.dayEnd) date.plusDays(1) else date).atTime(t).atZone(s.zone).toInstant() }
                    tx.execute(
                        "INSERT OR IGNORE INTO journal_lines (id, user_id, entry_id, stamped_at, text, edited_at, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        listOf(lineId, owner.ifEmpty { user().toString() }, entry, at?.toString(), line.text.take(MAX_LINE), now.toString(), n),
                    )
                }
                tx.execute("UPDATE journal_entries SET text = '' WHERE id = ?", listOf(entry))
            }
        }
    }

    /** One mood per day (any day up to today; the journal stays editable). */
    override suspend fun setMood(date: LocalDate, mood: Int?) {
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
    override suspend fun addPhoto(date: LocalDate, photo: PreparedPhoto): UUID {
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
    override suspend fun deletePhoto(id: UUID) {
        val today = dateAt(clock.now().instant)
        val path: String? = db.oneTransaction { tx ->
            tx.getOptional(
                "SELECT e.id, e.loopdo_date, coalesce(e.text, '') FROM journal_photos p JOIN journal_entries e ON e.id = p.entry_id WHERE p.id = ?", listOf(id.toString()),
            ) { Triple(it.getString(0)!!, LocalDate.parse(it.getString(1)!!.take(10)), it.getString(2) ?: "") }?.let { (entry, date, text) ->
                check(date >= today || parseLines(text).isNotEmpty() || tx.liveLineCount(entry) > 0 || tx.photoCount(entry) > 1) { KEEP_ONE }
            }
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

    private fun PowerSyncTransaction.liveLine(lineId: String, entryId: String): Boolean =
        getOptional("SELECT 1 FROM journal_lines WHERE id = ? AND entry_id = ? AND deleted_at IS NULL", listOf(lineId, entryId)) { true } ?: false

    private fun PowerSyncTransaction.liveLineCount(entryId: String): Long =
        get("SELECT count(*) FROM journal_lines WHERE entry_id = ? AND deleted_at IS NULL", listOf(entryId)) { it.getLong(0)!! }

    private fun PowerSyncTransaction.photoCount(entryId: String): Long =
        get("SELECT count(*) FROM journal_photos WHERE entry_id = ?", listOf(entryId)) { it.getLong(0)!! }

    private fun PowerSyncTransaction.insertEntry(id: String, date: LocalDate, text: String) = execute(
        "INSERT INTO journal_entries (id, user_id, loopdo_date, text) VALUES (?, ?, ?, ?)",
        listOf(id, user().toString(), date.toString(), text),
    )


    private companion object {
        const val MAX_LINE = 20_000 // journal_lines.text check
        const val CHANGED = "that line changed. try again."
        const val MAX_PHOTOS = 4 // journal_photos_limit trigger
        const val KEEP_ONE = "a past day keeps at least one line or photo. edit it instead."
    }
}
