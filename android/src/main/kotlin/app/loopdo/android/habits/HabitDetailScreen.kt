package app.loopdo.android.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loopdo.android.today.short
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Locked app-screens-v2, screen 2: one habit, its numbers and the month heatmap. */
@Composable
fun HabitDetailScreen(
    d: HabitDetail,
    today: LocalDate,
    canEdit: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onMonth: (YearMonth) -> Unit,
) {
    val first = d.since?.let(YearMonth::from) ?: d.month
    val prev = d.month.minusMonths(1).takeIf { it >= first }
    val next = d.month.plusMonths(1).takeIf { it <= YearMonth.from(today).plusMonths(1) }
    val toPrev by rememberUpdatedState(prev)
    val toNext by rememberUpdatedState(next)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Bracket("<", idle = true, onClick = onBack)
            Text("~/habits/${d.title}", style = LoopDoType.title.copy(color = LoopDoColors.accent), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (canEdit) Bracket("edit", idle = true, onClick = onEdit)
        }
        Text(d.scheduleLine, style = LoopDoType.body, modifier = Modifier.padding(vertical = 8.dp))
        DashedRule(Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Stat("streak", d.streak.toString(), Modifier.weight(1f))
            Stat("best", d.best.toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Stat("freezes", "❄ ${d.freezes}/2", Modifier.weight(1f))
            Stat("done", d.donePercent?.let { "$it%" } ?: "–", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Bracket("< ${prev?.short() ?: d.month.minusMonths(1).short()}", idle = true, enabled = prev != null) { prev?.let(onMonth) }
            Text(
                d.month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase(Locale.ENGLISH) + " " + d.month.year,
                style = LoopDoType.label.copy(color = LoopDoColors.text), textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Bracket("${next?.short() ?: d.month.plusMonths(1).short()} >", idle = true, enabled = next != null) { next?.let(onMonth) }
        }
        // Swipe the calendar left or right for the next or previous month.
        Column(Modifier.pointerInput(Unit) {
            var dx = 0f
            detectHorizontalDragGestures(
                onDragStart = { dx = 0f },
                onDragEnd = {
                    if (dx < -SWIPE) toNext?.let(onMonth) else if (dx > SWIPE) toPrev?.let(onMonth)
                },
            ) { change, amount -> change.consume(); dx += amount }
        }) {
            Row(Modifier.fillMaxWidth()) {
                DayOfWeek.entries.forEach {
                    Text(it.short().take(1), textAlign = TextAlign.Center, style = LoopDoType.label, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                }
            }
            val lead = d.month.atDay(1).dayOfWeek.value - 1 // monday first
            (List(lead) { null } + d.cells + List((7 - (lead + d.cells.size) % 7) % 7) { null }).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { cell -> Box(Modifier.weight(1f).padding(3.dp).aspectRatio(1f)) { cell?.let { DayCell(it) } } }
                }
            }
        }
        Text("▮ done · □ missed · ❄ freeze · dim off-day", style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp), modifier = Modifier.padding(vertical = 8.dp))
        DashedRule(Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (d.month == YearMonth.from(today)) "THIS MONTH" else d.month.short().uppercase(Locale.ENGLISH), style = LoopDoType.label, modifier = Modifier.weight(1f))
            Text("${d.monthDone}/${d.monthDue}" + if (d.monthFrozen > 0) " · ${d.monthFrozen} frozen" else "", style = LoopDoType.small.copy(color = LoopDoColors.dim))
        }
        val dimLine = LoopDoType.body.copy(color = LoopDoColors.dim)
        val pad = Modifier.padding(top = 10.dp)
        d.nextDue?.let { next ->
            Text("next " + (if (next == today) "today" else next.short()) + (d.reminder?.let { " @$it" } ?: ""), style = dimLine, modifier = pad)
        }
        Text(d.toNextFreeze?.let { "earns ❄ in $it more done ${if (it == 1) "day" else "days"}" } ?: "❄ both freezes held", style = dimLine, modifier = pad)
        d.since?.let { Text("since ${it.format(LONG).lowercase(Locale.ENGLISH)} · ${d.totalDone} done", style = dimLine, modifier = pad) }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = LoopDoColors.dim)) { append("$label ") }
            append(value)
        },
        style = LoopDoType.body, modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun DayCell(c: MonthCell) {
    val shape = RoundedCornerShape(6.dp)
    val base = Modifier.fillMaxSize()
    val box = when (c.state) {
        CellState.DONE -> base.background(LoopDoColors.accent.copy(alpha = 0.75f), shape)
        CellState.MISSED -> base.border(1.dp, LoopDoColors.warn.copy(alpha = 0.8f), shape)
        CellState.FROZEN -> base.background(LoopDoColors.accent.copy(alpha = 0.18f), shape)
        CellState.PENDING, CellState.OFF, CellState.FUTURE -> base
    }.then(if (c.today) Modifier.border(1.5.dp, LoopDoColors.accent, shape) else Modifier)
    val color = when (c.state) {
        CellState.DONE -> Color(0xFF0B1220)
        CellState.MISSED, CellState.FROZEN, CellState.PENDING -> LoopDoColors.text
        CellState.OFF -> LoopDoColors.dim
        CellState.FUTURE -> LoopDoColors.dim
    }
    Box(box.then(if (c.state == CellState.FUTURE) Modifier.alpha(0.45f) else Modifier), contentAlignment = Alignment.Center) {
        Text(c.date.dayOfMonth.toString(), style = LoopDoType.small.copy(color = color))
        if (c.state == CellState.FROZEN) Text("❄", style = LoopDoType.small.copy(fontSize = 9.sp, color = LoopDoColors.accent), modifier = Modifier.align(Alignment.TopEnd).padding(end = 3.dp))
    }
}

private fun YearMonth.short(): String = month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase(Locale.ENGLISH)
private val LONG = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private const val SWIPE = 80f
