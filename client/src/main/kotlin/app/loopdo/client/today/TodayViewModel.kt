package app.loopdo.client.today

import app.loopdo.client.today.toModel

import app.loopdo.client.today.HabitItem
import app.loopdo.client.today.StaleDayException
import app.loopdo.client.today.TaskItem
import app.loopdo.client.today.TodayActions
import app.loopdo.client.today.TodayModel
import app.loopdo.client.today.TodayRows
import app.loopdo.client.today.ticker

import app.loopdo.client.log.logE
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.loopdo.client.sync.message
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
    /** "All done": a count habit jumps to today's target. */
    fun complete(h: HabitItem)
    /** The exact count (long-press → type), or a correction. */
    fun setCount(h: HabitItem, count: Int)
    fun toggleTask(t: TaskItem)
    /** [onSaved] runs only once the text is safely stored; until then the add sheet keeps it. */
    fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: String?, listName: String?, today: LocalDate, repeat: app.loopdo.shared.task.RepeatRule? = null, onSaved: () -> Unit)
    fun appendJournal(text: String, onSaved: () -> Unit)
    fun undo(s: Snack)
    fun dismissSnack(id: Long)
    /** Debug builds only: a tick with a forged anchor signature (exit check 5). */
    fun debugForgedTick() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    rows: kotlinx.coroutines.flow.Flow<TodayRows>,
    private val actions: TodayActions,
    notices: app.loopdo.client.sync.SetAsideNotices,
    private val now: () -> Instant,
) : ViewModel(), TodayUi {


    /** Rebuilt whenever the data changes, every minute, and exactly at day end (the ticker). */
    val state: StateFlow<TodayModel?> = rows.flatMapLatest { rows ->
        if (rows.versions.isEmpty()) return@flatMapLatest flowOf(null)
        ticker(now) { dayEndAt(rows.versions, now()) }.map { t -> rows.toModel(t)?.first }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _snack = MutableStateFlow<Snack?>(null)
    val snack: StateFlow<Snack?> = _snack.asStateFlow()

    init {
        viewModelScope.launch {
            notices.pending.collect { list ->
                if (list.isEmpty()) return@collect
                val text = if (list.size == 1) list[0].message() else "${list.size} changes were refused by the server and undone."
                _snack.value = Snack(text, null)
                notices.consume(list)
            }
        }
    }

    override fun dismissSnack(id: Long) { _snack.compareAndSet(_snack.value?.takeIf { it.id == id }, null) }

    override fun undo(s: Snack) {
        _snack.value = null
        s.undo?.let { u -> run { u() } }
    }

    /** The day this screen shows (null before the first build): ticks for an ended day are refused. */
    private fun shownDate(): LocalDate? = state.value?.date

    /** A plain message in the snackbar (e.g. sign-out refused offline). */
    fun showMessage(text: String) { _snack.value = Snack(text, null) }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch { attempt(block) }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: StaleDayException) {
            _snack.value = Snack("the day just ended, so that wasn't ticked. this is the new day.", null)
        } catch (e: IllegalArgumentException) {
            _snack.value = Snack(e.message ?: "that didn't work.", null)
        } catch (e: Exception) {
            logE("loopdo_today", "action failed", e)
            _snack.value = Snack("couldn't save that. try again.", null)
        }
    }

    /** Tap on an open habit (or [+1]). */
    override fun tick(h: HabitItem) = run {
        val id = UUID.fromString(h.id)
        val r = actions.tickHabit(id, shownDate(), by = if (h.kind == HabitKind.COUNT) h.step else 1) ?: return@run
        _snack.value = Snack(if (h.kind == HabitKind.COUNT) "+${r.second - r.first}" else "ticked", undo = { actions.setHabitCount(id, r.first, shownDate()) })
    }

    override fun complete(h: HabitItem) = run {
        val id = UUID.fromString(h.id)
        val r = actions.completeHabit(id, shownDate()) ?: return@run
        _snack.value = Snack("all done", undo = { actions.setHabitCount(id, r.first, shownDate()) })
    }

    override fun setCount(h: HabitItem, count: Int) = run {
        val id = UUID.fromString(h.id)
        val r = actions.setHabitCount(id, count, shownDate()) ?: return@run
        _snack.value = Snack("set to ${r.second}", undo = { actions.setHabitCount(id, r.first, shownDate()) })
    }

    /** Tap on a done yes/no habit. */
    override fun untick(h: HabitItem) = run {
        val id = UUID.fromString(h.id)
        if (actions.untickHabit(id, shownDate()) != null) _snack.value = Snack("unticked", undo = { actions.tickHabit(id, shownDate()) })
    }

    /** Long-press on a count habit: the same-day −1 correction. */
    override fun decrement(h: HabitItem) = run {
        val id = UUID.fromString(h.id)
        if (actions.decrementHabit(id, shownDate()) != null) _snack.value = Snack("−1", undo = { actions.tickHabit(id, shownDate()) })
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

    override fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: String?, listName: String?, today: LocalDate, repeat: app.loopdo.shared.task.RepeatRule?, onSaved: () -> Unit) = run {
        if (repeat != null) {
            actions.addRepeatingTask(title, due, time, listId?.let(UUID::fromString), repeat)
            onSaved()
            _snack.value = Snack("added. ${app.loopdo.client.tasks.repeatLine(repeat, due ?: today)}. it's under ↻ repeating.", null)
            return@run
        }
        actions.addTask(title, due, time, listId?.let(UUID::fromString))
        onSaved()
        val where = when {
            due == today -> "today"
            due == today.plusDays(1) -> "tomorrow"
            due != null -> due.toString()
            listName != null -> "# $listName"
            else -> "inbox"
        }
        _snack.value = Snack("added to $where", null)
    }

    override fun debugForgedTick() = run {
        val h = state.value?.habitsOpen?.firstOrNull() ?: return@run
        if (actions.debugForgedTick(UUID.fromString(h.id), shownDate()) != null) _snack.value = Snack("debug: forged tick sent", null)
    }

    override fun appendJournal(text: String, onSaved: () -> Unit) = run {
        actions.appendJournalLine(text)
        onSaved()
        _snack.value = Snack("added to today's journal", null)
    }
}
