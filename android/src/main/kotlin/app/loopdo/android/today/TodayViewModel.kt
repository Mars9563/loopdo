package app.loopdo.android.today

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.loopdo.shared.day.dateResolver
import app.loopdo.shared.day.dayEndAt
import app.loopdo.shared.habit.HabitKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** A bottom message, optionally with `[undo]`. [id] restarts the hide timer. */
data class Snack(val text: String, val undo: (suspend () -> Unit)?, val id: Long = System.nanoTime())

/** What the today screen can ask for (the view model, or a no-op for the debug demo). */
interface TodayUi {
    fun tick(h: HabitItem)
    fun untick(h: HabitItem)
    fun decrement(h: HabitItem)
    fun toggleTask(t: TaskItem)
    fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: String?, listName: String?, today: LocalDate)
    fun appendJournal(text: String)
    fun undo(s: Snack)
    fun dismissSnack(id: Long)
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(data: TodayData, private val actions: Actions, private val now: () -> Instant) : ViewModel(), TodayUi {

    /** Rebuilt whenever the data changes, every minute, and exactly at day end (the ticker). */
    val state: StateFlow<TodayModel?> = data.rows().flatMapLatest { rows ->
        if (rows.versions.isEmpty()) return@flatMapLatest flowOf(null)
        val dateAt = dateResolver(rows.versions)
        ticker(now) { dayEndAt(rows.versions, now()) }.map { t ->
            val today = dateAt(t)
            buildToday(
                now = t, dateAt = dateAt, dayEndAt = dayEndAt(rows.versions, t),
                since = rows.onboardedAt?.let(dateAt)?.coerceAtMost(today) ?: today,
                habits = rows.habits, schedules = rows.schedules, logs = rows.logs, tasks = rows.tasks, lists = rows.lists,
                journalDays = rows.journalDays, activeDays = rows.activeDays,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _snack = MutableStateFlow<Snack?>(null)
    val snack: StateFlow<Snack?> = _snack.asStateFlow()

    override fun dismissSnack(id: Long) { _snack.compareAndSet(_snack.value?.takeIf { it.id == id }, null) }

    override fun undo(s: Snack) {
        _snack.value = null
        s.undo?.let { u -> run { u() } }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch { attempt(block) }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            _snack.value = Snack(e.message ?: "that didn't work.", null)
        } catch (e: Exception) {
            Log.e("loopdo_today", "action failed", e)
            _snack.value = Snack("couldn't save that. try again.", null)
        }
    }

    /** Tap on an open habit (or [+1]). */
    override fun tick(h: HabitItem) = run {
        val id = UUID.fromString(h.id)
        if (actions.tickHabit(id) != null) {
            _snack.value = Snack("ticked", undo = { if (h.kind == HabitKind.COUNT) actions.decrementHabit(id) else actions.untickHabit(id) })
        }
    }

    /** Tap on a done yes/no habit. */
    override fun untick(h: HabitItem) = run {
        val id = UUID.fromString(h.id)
        if (actions.untickHabit(id) != null) _snack.value = Snack("unticked", undo = { actions.tickHabit(id) })
    }

    /** Long-press on a count habit: the same-day −1 correction. */
    override fun decrement(h: HabitItem) = run {
        val id = UUID.fromString(h.id)
        if (actions.decrementHabit(id) != null) _snack.value = Snack("−1", undo = { actions.tickHabit(id) })
    }

    override fun toggleTask(t: TaskItem) = run {
        val id = UUID.fromString(t.id)
        if (!t.done) {
            if (actions.completeTask(id)) _snack.value = Snack("ticked", undo = { actions.reopenTask(id) })
        } else if (actions.reopenTask(id)) {
            _snack.value = Snack("unticked", undo = { actions.completeTask(id) })
        } else {
            _snack.value = Snack("a dated task can only be unticked on the day it was done.", null)
        }
    }

    override fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: String?, listName: String?, today: LocalDate) = run {
        actions.addTask(title, due, time, listId?.let(UUID::fromString))
        val where = when {
            due == today -> "today"
            due == today.plusDays(1) -> "tomorrow"
            due != null -> due.toString()
            listName != null -> "# $listName"
            else -> "inbox"
        }
        _snack.value = Snack("added to $where", null)
    }

    override fun appendJournal(text: String) = run {
        actions.appendJournalLine(text)
        _snack.value = Snack("added to today's journal", null)
    }
}
