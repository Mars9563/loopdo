package app.loopdo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.loopdo.client.habits.CellState
import app.loopdo.client.habits.HabitDetail
import app.loopdo.client.habits.HabitLine
import app.loopdo.client.habits.HabitsData
import app.loopdo.client.habits.habitDetail
import app.loopdo.client.today.short
import java.time.LocalDate
import java.time.YearMonth

/** 16 weeks of one habit, oldest first, built from the phone's month cells. */
fun weeksGrid(data: HabitsData, id: String, weeks: Int = 16): List<Pair<LocalDate, CellState>> {
    val row = data.habit(id) ?: return emptyList()
    val end = data.today
    val start = end.minusWeeks(weeks.toLong() - 1).with(java.time.DayOfWeek.MONDAY)
    val months = generateSequence(YearMonth.from(start)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(end) }
    val byDate = months.flatMap { m -> habitDetail(row, data.schedules, data.logs, data.today, m)?.cells.orEmpty() }.associate { it.date to it.state }
    return generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end.with(java.time.DayOfWeek.SUNDAY) }.map { it to (byDate[it] ?: CellState.FUTURE) }.toList()
}

/** The locked desktop-screens-v1 F: the list | the picked habit's streak, freezes, 16 weeks and completion. */
@Composable
fun HabitsPane(data: HabitsData, selected: String?, onSelect: (String) -> Unit, onNew: () -> Unit, onEdit: (String) -> Unit, onRestore: (String) -> Unit) {
    val t = LocalType.current
    val list = data.list
    val pick = selected?.takeIf { id -> data.habit(id) != null } ?: list.active.firstOrNull()?.id
    var showArchived by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("~/habits", style = t.title.copy(color = Fern.mint))
            Spacer(Modifier.width(14.dp))
            BasicText("${list.active.size} habits", style = t.title.copy(color = Fern.hi))
            Spacer(Modifier.weight(1f))
            BasicText("[+ habit]", style = t.small.copy(color = Fern.mint), modifier = Modifier.clickable(onClick = onNew))
        }
        Row(Modifier.fillMaxSize().padding(top = 6.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().dashedRight().padding(top = 14.dp, end = 26.dp).verticalScroll(rememberScrollState())) {
                Label("active", "streak")
                if (list.active.isEmpty()) BasicText("no habits yet. [+ habit] adds one.", style = t.small)
                list.active.forEach { h -> HabitListRow(h, h.id == pick) { onSelect(h.id) } }
                if (list.paused.isNotEmpty()) { Spacer(Modifier.height(14.dp)); Label("paused"); list.paused.forEach { h -> HabitListRow(h, h.id == pick) { onSelect(h.id) } } }
                Spacer(Modifier.height(14.dp))
                Label("archived", rightContent = { BasicText(if (showArchived) "[^]" else "[v]", style = t.label.copy(color = Fern.ink), modifier = Modifier.clickable { showArchived = !showArchived }) })
                if (showArchived) list.archived.forEach { h ->
                    ItemRow("", AnnotatedString(h.title), done = true) { BasicText("[restore]", style = t.small.copy(color = Fern.mint), modifier = Modifier.clickable { onRestore(h.id) }) }
                }
            }
            Column(Modifier.weight(1.25f).fillMaxHeight().padding(top = 14.dp, start = 26.dp).verticalScroll(rememberScrollState())) {
                val row = pick?.let(data::habit)
                val d = row?.let { habitDetail(it, data.schedules, data.logs, data.today, YearMonth.from(data.today)) }
                if (d == null) BasicText("pick a habit on the left.", style = t.small) else HabitDetailView(d, weeksGrid(data, d.id), data.today) { onEdit(d.id) }
            }
        }
    }
}

@Composable
private fun HabitListRow(h: HabitLine, on: Boolean, onClick: () -> Unit) {
    val t = LocalType.current
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Fern.mint.copy(alpha = .08f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(h.title, style = t.body.copy(color = if (on) Fern.hi else Fern.ink), modifier = Modifier.weight(1f), maxLines = 1)
        BasicText(buildAnnotatedString { withStyle(SpanStyle(color = Fern.ink)) { append("${h.streak}d") }; h.note?.let { append(" $it") } }, style = t.small)
    }
}

@Composable
private fun HabitDetailView(d: HabitDetail, grid: List<Pair<LocalDate, CellState>>, today: LocalDate, onEdit: () -> Unit) {
    val t = LocalType.current
    Label("${d.title} · ${d.scheduleLine}", rightContent = { BasicText("[edit]", style = t.label.copy(color = Fern.mint), modifier = Modifier.clickable(onClick = onEdit)) })
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(buildAnnotatedString { append("streak "); withStyle(SpanStyle(color = Fern.hi)) { append("${d.streak}d") }; append(" · best ${d.best}d") }, style = t.body)
        Spacer(Modifier.weight(1f))
        BasicText(buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = Glyphs, color = Fern.mint)) { append("❄︎") }
            append(" ${d.freezes} freeze${if (d.freezes == 1) "" else "s"} held")
            d.toNextFreeze?.let { append(" · next in ${it}d") }
        }, style = t.small)
    }
    Label("last 16 weeks", rightContent = {
        BasicText(buildAnnotatedString {
            withStyle(SpanStyle(color = Fern.mint)) { append("■") }; append(" done  ")
            withStyle(SpanStyle(color = Fern.ember)) { append("■") }; append(" missed  ")
            withStyle(SpanStyle(color = Fern.mint.copy(alpha = .45f))) { append("■") }; append(" freeze")
        }, style = t.label)
    })
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        grid.chunked(7).forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { (date, s) ->
                    val c = when (s) {
                        CellState.DONE -> Fern.mint.copy(alpha = .85f)
                        CellState.MISSED -> Fern.ember.copy(alpha = .55f)
                        CellState.FROZEN -> Fern.mint.copy(alpha = .30f)
                        CellState.PENDING -> Fern.ink.copy(alpha = .14f)
                        else -> Fern.ink.copy(alpha = .06f)
                    }
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(if (date > today) Color.Transparent else c))
                }
            }
        }
    }
    val last30 = grid.filter { it.first > today.minusDays(30) && it.first <= today }
    val due30 = last30.count { it.second in setOf(CellState.DONE, CellState.MISSED, CellState.FROZEN) }
    val pct30 = if (due30 == 0) null else last30.count { it.second == CellState.DONE } * 100 / due30
    Label("completion")
    Bar("30 days", pct30)
    Bar("all time", d.donePercent)
    d.nextDue?.let { BasicText("next due ${if (it == today) "today" else it.short()}", style = t.small, modifier = Modifier.padding(top = 10.dp)) }
}

@Composable
fun Bar(label: String, pct: Int?, negative: Boolean = false, value: String? = null) {
    val t = LocalType.current
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = t.body, modifier = Modifier.width(150.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Fern.ink.copy(alpha = .08f))) {
            if (pct != null) Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (negative) Fern.ember.copy(alpha = .8f) else Fern.leaf))
        }
        BasicText(value ?: pct?.let { "$it%" } ?: "–", style = t.body.copy(color = Fern.hi), modifier = Modifier.width(54.dp).padding(start = 12.dp))
    }
}
