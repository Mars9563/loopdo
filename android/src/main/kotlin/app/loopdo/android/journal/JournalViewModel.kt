package app.loopdo.android.journal

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.loopdo.android.today.TodayRows
import app.loopdo.android.today.parseInstant
import app.loopdo.android.today.ticker
import app.loopdo.android.today.toModel
import app.loopdo.shared.day.dayEndAt
import app.loopdo.shared.day.settingsAt
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Watched journal rows (entries, and photos with this phone's own file when it has one). */
class JournalData(private val db: PowerSyncDatabase) {
    fun entries(): Flow<List<EntryRow>> =
        db.watch("SELECT id, loopdo_date, coalesce(text, ''), mood FROM journal_entries") {
            EntryRow(it.getString(0)!!, LocalDate.parse(it.getString(1)!!.take(10)), it.getString(2) ?: "", it.getLong(3)?.toInt())
        }

    fun lines(): Flow<List<LineRow>> =
        db.watch("SELECT id, entry_id, stamped_at, coalesce(text, ''), coalesce(sort_order, 0) FROM journal_lines WHERE deleted_at IS NULL") {
            LineRow(it.getString(0)!!, it.getString(1)!!, it.getString(2)?.let(::parseInstant), it.getString(3) ?: "", it.getLong(4)?.toInt() ?: 0)
        }

    fun photos(): Flow<List<PhotoRow>> = db.watch(
        "SELECT p.id, p.entry_id, p.added_at, f.path FROM journal_photos p LEFT JOIN photo_files f ON f.id = p.id ORDER BY p.sort_order",
    ) { PhotoRow(it.getString(0)!!, it.getString(1)!!, it.getString(2)?.let(::parseInstant), it.getString(3)) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModel(
    rows: Flow<TodayRows>,
    data: JournalData,
    private val actions: JournalCommands,
    private val now: () -> Instant,
    private val photoFile: suspend (String, String?) -> File?,
    private val uploadSoon: () -> Unit,
    /** The unsaved line, shared with the widget pop-up and kept on disk (review of M6: never lost to a tab switch or the lock). */
    private val drafts: LineDrafts? = null,
) : ViewModel() {

    fun draftFor(date: LocalDate): String = drafts?.line(date)?.first.orEmpty()
    fun keepDraft(date: LocalDate, text: String) { drafts?.keep(date, text, now(), earlierDay = false) }

    /** The time log, rebuilt on every change and at the day end (the ticker). */
    val state: StateFlow<JournalModel?> = rows.flatMapLatest { r ->
        if (r.versions.isEmpty()) return@flatMapLatest flowOf(null)
        combine(ticker(now) { dayEndAt(r.versions, now()) }, data.entries(), data.lines(), data.photos()) { t, entries, lines, photos ->
            val (today, _) = r.toModel(t) ?: return@combine null
            val s = settingsAt(r.versions, t)
            buildJournal(
                entries, photos, today.date, s.zone, s.dayEnd, today.streaks.journal, lines,
                zoneOn = { d -> settingsAt(r.versions, d.atTime(12, 0).atZone(java.time.ZoneOffset.UTC).toInstant()).zone },
            ).copy(now = t.atZone(s.zone).toLocalTime())
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    data class Snack(val text: String, val id: Long = System.nanoTime())
    private val _snack = MutableStateFlow<Snack?>(null)
    val snack: StateFlow<Snack?> = _snack.asStateFlow()
    fun dismiss(id: Long) { _snack.compareAndSet(_snack.value?.takeIf { it.id == id }, null) }

    /** One action; a refusal (plain words) goes to [onError] and the screen keeps what was typed. */
    private fun run(onError: (String) -> Unit = { _snack.value = Snack(it) }, onDone: () -> Unit = {}, work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work(); onDone()
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: GENERIC)
            } catch (e: IllegalStateException) {
                onError(e.message ?: GENERIC)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("loopdo_journal", "journal action failed", e)
                onError(GENERIC)
            }
        }
    }

    /** A line save in flight: a double tap on [save ↵] saves once (M6 minor). */
    private var savingLine = false

    fun addLines(date: LocalDate, input: String, onSaved: () -> Unit, onError: (String) -> Unit) {
        if (savingLine) return
        savingLine = true
        run({ savingLine = false; onError(it) }, { savingLine = false; drafts?.clear(date); onSaved() }) { actions.addLines(date, input) }
    }
    fun editLine(date: LocalDate, lineId: String, new: String, onSaved: () -> Unit, onError: (String) -> Unit) = run(onError, onSaved) { actions.editLine(date, lineId, new) }
    fun deleteLine(date: LocalDate, lineId: String) = run { actions.deleteLine(date, lineId) }
    fun setMood(date: LocalDate, mood: Int?) = run { actions.setMood(date, mood) }
    fun deletePhoto(id: String) = run { actions.deletePhoto(UUID.fromString(id)) }

    /** Compresses ([prepare], off the main thread) and adds a photo to [date]; then asks for an upload. */
    fun addPhoto(date: LocalDate, afterSaved: () -> Unit = {}, prepare: suspend () -> PreparedPhoto) = run {
        val p = withContext(Dispatchers.IO) { prepare() }
        try {
            actions.addPhoto(date, p)
        } catch (e: Exception) {
            File(p.path).delete() // refused (e.g. a fifth photo): no stray compressed copy; the original is kept
            throw e
        }
        afterSaved() // e.g. the camera's original, removed only once the photo is stored
        uploadSoon()
    }

    /** The photo's file: this phone's own, the cache, or a download (null while it isn't uploaded yet). */
    suspend fun photo(id: String, localPath: String?): File? = withContext(Dispatchers.IO) { photoFile(id, localPath) }

    private companion object {
        const val GENERIC = "couldn't save that. try again."
    }
}
