package app.loopdo.android.popup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.loopdo.android.today.TodayActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * What both pop-ups do: the same actions as the app. A pop-up closes ([onSaved]) only once the
 * words are stored. On a refusal it shows the reason and keeps the draft.
 */
class PopupViewModel(
    private val actions: TodayActions,
    private val drafts: PopupDrafts,
    private val redraw: suspend () -> Unit,
) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun draft(key: String): String = drafts.load(key)
    fun keepDraft(key: String, text: String) = drafts.save(key, text)

    fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: String?, repeat: app.loopdo.shared.task.RepeatRule? = null, onSaved: () -> Unit) =
        save(ADD, onSaved) {
            if (repeat != null) actions.addRepeatingTask(title, due, time, listId?.let(UUID::fromString), repeat)
            else actions.addTask(title, due, time, listId?.let(UUID::fromString))
        }

    fun appendJournal(text: String, onSaved: () -> Unit) = save(ADD, onSaved) { actions.appendJournalLine(text) }

    fun saveJournal(date: LocalDate, text: String, mood: Int?, base: String? = null, onSaved: () -> Unit) =
        save(journalKey(date), onSaved) {
            actions.saveJournal(date, text, mood, base)
            drafts.save(baseKey(date), "")
        }

    /** [date]'s unsaved journal text and the entry text it started from, or null. */
    fun journalDraft(date: LocalDate): Pair<String, String>? =
        drafts.load(journalKey(date)).takeIf { it.isNotEmpty() }?.let { it to drafts.load(baseKey(date)) }

    /** Keeps [text] as [date]'s draft; text equal to its [base] is no draft at all. */
    fun keepJournalDraft(date: LocalDate, text: String, base: String) {
        drafts.save(journalKey(date), if (text == base) "" else text)
        drafts.save(baseKey(date), if (text == base) "" else base)
    }

    /** Days with unsaved journal text. */
    fun journalDraftDates(): Set<LocalDate> = drafts.keys()
        .filter { it.startsWith("journal:") && !it.endsWith(":base") && drafts.load(it).isNotEmpty() }
        .mapNotNull { runCatching { LocalDate.parse(it.removePrefix("journal:")) }.getOrNull() }.toSet()

    private fun save(key: String, onSaved: () -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: IllegalArgumentException) {
                _error.value = e.message ?: "that didn't work."
                return@launch
            } catch (e: Exception) {
                _error.value = "couldn't save that. try again."
                return@launch
            }
            drafts.save(key, "")
            _error.value = null
            runCatching { redraw() }
            onSaved()
        }
    }

    companion object {
        const val ADD = "add"
        fun journalKey(date: LocalDate) = "journal:$date"
        private fun baseKey(date: LocalDate) = "journal:$date:base"
    }
}
