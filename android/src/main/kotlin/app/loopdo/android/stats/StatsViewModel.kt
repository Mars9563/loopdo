package app.loopdo.android.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.loopdo.shared.day.dateResolver
import app.loopdo.shared.stats.PixelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** The stats tab and the weekly review (M8). Everything shown comes from [buildStats] / [buildReview]. */
class StatsViewModel(
    rows: Flow<StatsRows>,
    private val focus: FocusActions,
    private val now: () -> Instant,
    /** The review was opened on [today] (a Sunday silences that Sunday's ping). */
    private val onReviewOpened: (LocalDate) -> Unit = {},
) : ViewModel() {
    private val mode = MutableStateFlow(PixelMode.MOOD)
    private val year = MutableStateFlow<Int?>(null)
    private val last30 = MutableStateFlow(true)
    /** null = the review is closed; else the week shown (null inside = the default week). */
    private val reviewWeek = MutableStateFlow<Pair<Boolean, LocalDate?>>(false to null)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // M8 review: built off the main thread, and only while the stats tab is on screen.
    private val shared = rows.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun today(r: StatsRows) = dateResolver(r.versions)(now())

    val stats: StateFlow<StatsUi?> = combine(shared, mode, year, last30) { r, m, y, l ->
        if (r == null || r.versions.isEmpty()) null else today(r).let { t -> buildStats(r, t, y ?: t.year, m, l) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val review: StateFlow<ReviewUi?> = combine(shared, reviewWeek) { r, (open, week) ->
        if (!open || r == null || r.versions.isEmpty()) null else buildReview(r, today(r), week)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMode(m: PixelMode) { mode.value = m }
    fun shiftYear(by: Int) { stats.value?.let { year.value = it.year + by } }
    fun setLast30(on: Boolean) { last30.value = on }

    fun openReview() {
        reviewWeek.value = true to null
        shared.value?.takeIf { it.versions.isNotEmpty() }?.let { onReviewOpened(today(it)) }
    }
    fun closeReview() { reviewWeek.value = false to null; _message.value = null }
    /** [by] weeks back (−1) or forward (+1); never past the week the review opens on. */
    fun page(by: Int) {
        val r = review.value ?: return
        if (by > 0 && !r.canGoForward) return
        reviewWeek.value = true to r.review.start.plusWeeks(by.toLong())
    }

    fun addFocus(item: PickItem, goal: Int?) = act {
        val week = review.value?.nextWeek ?: return@act Result.success(Unit)
        focus.add(week, item.kind, item.itemId, item.title, goal ?: item.goal, item.deadline).map { }
    }
    fun removeFocus(id: String) = act { focus.remove(id) }

    private fun act(block: suspend () -> Result<Unit>) = viewModelScope.launch {
        _message.value = block().exceptionOrNull()?.let { it.message ?: "couldn't save that. try again." }
    }
    fun dismissMessage() { _message.value = null }
}
