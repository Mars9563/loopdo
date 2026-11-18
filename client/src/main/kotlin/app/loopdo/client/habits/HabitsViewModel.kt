package app.loopdo.client.habits

import app.loopdo.client.habits.HabitCommands
import app.loopdo.client.habits.HabitDraft
import app.loopdo.client.habits.HabitsList
import app.loopdo.client.habits.buildHabitsList
import app.loopdo.client.habits.short

import app.loopdo.client.log.logW
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.loopdo.client.today.HabitRow
import app.loopdo.client.today.LogRow
import app.loopdo.client.today.ScheduleRow
import app.loopdo.client.today.TodayRows
import app.loopdo.client.today.ticker
import app.loopdo.shared.day.dateResolver
import app.loopdo.shared.day.dayEndAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The rows the habits screens are built from, and the LoopDo date they are judged on. */
data class HabitsData(val today: LocalDate, val habits: List<HabitRow>, val schedules: List<ScheduleRow>, val logs: List<LogRow>) {
    val list: HabitsList by lazy { buildHabitsList(habits, schedules, logs, today) }
    fun habit(id: String): HabitRow? = habits.firstOrNull { it.id == id && it.deletedAt == null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HabitsViewModel(
    rows: Flow<TodayRows>,
    private val actions: HabitCommands,
    private val now: () -> Instant,
) : ViewModel() {

    /** Rebuilt when the data changes and when the LoopDo day rolls over. */
    val data: StateFlow<HabitsData?> = rows.flatMapLatest { r ->
        if (r.versions.isEmpty()) return@flatMapLatest flowOf(null)
        val dateAt = dateResolver(r.versions)
        ticker(now) { dayEndAt(r.versions, now()) }.map { dateAt(it) }.distinctUntilChanged()
            .map { today -> HabitsData(today, r.habits, r.schedules, r.logs) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<Snack?>(null)
    /** A short bottom message ("archived. it stops from tomorrow."). */
    val message: StateFlow<Snack?> = _message.asStateFlow()

    data class Snack(val text: String, val id: Long = System.nanoTime())

    fun dismiss(id: Long) { _message.compareAndSet(_message.value?.takeIf { it.id == id }, null) }

    /**
     * Runs one action. A rule's refusal (plain message) goes to [onError] and nothing was written;
     * the screen keeps what was typed. Anything else is logged and shown as a retry message.
     */
    private fun run(done: String?, onError: (String) -> Unit = ::show, onDone: () -> Unit = {}, work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
                done?.let(::show)
                onDone()
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: GENERIC)
            } catch (e: IllegalStateException) {
                onError(e.message ?: GENERIC)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("loopdo_habits", "habit action failed", e)
                onError(GENERIC)
            }
        }
    }

    private fun show(text: String) { _message.value = Snack(text) }

    /**
     * Creates ([id] null) or edits a habit, then starts [pause] if one was picked. [onSaved] gets the
     * habit's id once everything is stored; on a refusal [onError] gets the message and the editor
     * keeps the draft.
     */
    fun save(id: UUID?, draft: HabitDraft, pause: ClosedRange<LocalDate>?, onSaved: (UUID) -> Unit, onError: (String) -> Unit) {
        var saved: UUID? = null
        run(null, onError, onDone = { onSaved(saved!!) }) {
            val habit = id?.also { actions.editHabit(it, draft) } ?: actions.createHabit(draft)
            saved = habit
            pause?.let { actions.pauseHabit(habit, it.start, it.endInclusive) }
        }
    }

    fun archive(id: UUID, onDone: () -> Unit) = run("archived. it stops from tomorrow; [restore] is under archived.", onDone = onDone) { actions.archiveHabit(id) }
    fun restore(id: UUID) = run("restored. it is due again from tomorrow.") { actions.restoreHabit(id) }
    fun deleteForever(id: UUID, onDone: () -> Unit = {}) = run("deleted. its past days still count in your streaks.", onDone = onDone) { actions.hideHabit(id) }
    fun endPause(id: UUID) = run("pause ended. it is due again from tomorrow.") { actions.endPause(id) }
    fun cancelPause(id: UUID) = run("pause cancelled.") { actions.cancelPause(id) }
    fun reorder(ids: List<UUID>) = run(null) { actions.reorderHabits(ids) }

    private companion object {
        const val GENERIC = "couldn't save that. try again."
    }
}
