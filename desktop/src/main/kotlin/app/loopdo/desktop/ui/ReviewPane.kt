package app.loopdo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.loopdo.client.stats.PickItem
import app.loopdo.client.stats.ReviewUi
import app.loopdo.client.stats.weekRange
import app.loopdo.shared.stats.FocusKind

/** What the review asks for (the phone's StatsViewModel). */
interface ReviewActions {
    fun page(by: Int)
    fun close()
    fun addFocus(item: PickItem, goal: Int?)
    fun removeFocus(id: String)
}

/**
 * The weekly review (same content and order as the locked phone weekly-review-v1 A, laid out for the laptop): how it
 * went, last week's promises checked, next week's focus (1–2, Sunday or Monday), and the picker (B) under it.
 */
@Composable
fun ReviewPane(r: ReviewUi, act: ReviewActions) {
    val t = LocalType.current
    val w = r.review
    var picking by remember(r.nextWeek) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("[‹ back]", style = t.small.copy(color = Fern.mint), modifier = Modifier.clickable { act.close() })
            Spacer(Modifier.width(14.dp))
            BasicText("~/stats/review", style = t.title.copy(color = Fern.mint))
            Spacer(Modifier.weight(1f))
            BasicText("[‹ ${w.prevNumber}]", style = t.small.copy(color = Fern.lichen), modifier = Modifier.clickable { act.page(-1) })
            Spacer(Modifier.width(10.dp))
            // The next week's real ISO number (53 → 1 at a new year), and only when there is a newer week to show.
            if (r.canGoForward) BasicText("[${app.loopdo.shared.stats.isoWeek(w.start.plusDays(7))} ›]", style = t.small.copy(color = Fern.lichen), modifier = Modifier.clickable { act.page(1) })
        }
        BasicText("week ${w.number} · ${weekRange(w.start)}" + if (r.isLastDay) " · today is its last day" else "", style = t.small, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.weight(1.2f).dashedRight().padding(end = 26.dp)) {
                Label("how it went")
                WeekSummary(w)
                WeekGrid(w, r.focusIds)
                BasicText(buildAnnotatedString {
                    append("tasks "); withStyle(SpanStyle(color = Fern.ink)) { append("${w.tasksDone} done") }
                    if (w.late.isNotEmpty()) { append(" · "); withStyle(SpanStyle(color = Fern.ember)) { append("${w.late.size} late") } }
                }, style = t.small, modifier = Modifier.padding(top = 6.dp))
                if (w.late.isNotEmpty()) BasicText("late " + w.late.joinToString(" · ") { "${it.title} (${it.daysLate}d)" }, style = t.small.copy(color = Fern.ember), modifier = Modifier.padding(top = 2.dp))
            }
            Column(Modifier.weight(1f).padding(start = 26.dp)) {
                Label("last week you said", if (r.lastWeekSaid.isEmpty()) "" else "${r.kept}/${r.lastWeekSaid.size} kept")
                if (r.lastWeekSaid.isEmpty()) BasicText("no focus was set for this week.", style = t.small)
                r.lastWeekSaid.forEach { BasicText(it, style = t.body, modifier = Modifier.padding(vertical = 4.dp)) }
                Spacer(Modifier.height(14.dp))
                Label("next week · ${weekRange(r.nextWeek)}", r.nextNote)
                r.next.forEach { f ->
                    ItemRow("", buildAnnotatedString {
                        withStyle(SpanStyle(color = Fern.mint)) { append("› ") }; append(f.title); f.goal?.let { append(" ${it}×") }
                    }) { if (r.canEdit) BasicText("[×]", style = t.small, modifier = Modifier.clickable { act.removeFocus(f.id) }) }
                }
                if (r.next.size < 2) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("[+ focus]", style = t.body.copy(color = if (r.canEdit) Fern.mint else Fern.moss),
                        modifier = if (r.canEdit) Modifier.clickable { picking = !picking } else Modifier)
                    Spacer(Modifier.width(10.dp))
                    BasicText("sun or mon only", style = t.label)
                }
                if (picking && r.canEdit) FocusPicker(r.picker, onPick = { item, goal -> act.addFocus(item, goal); picking = false }, onCancel = { picking = false })
            }
        }
    }
}

/** The focus picker (phone mock B): habits with "N times next week" (−/+), tasks done by their day. */
@Composable
fun FocusPicker(items: List<PickItem>, onPick: (PickItem, Int?) -> Unit, onCancel: () -> Unit) {
    val t = LocalType.current
    val goals = remember(items) { mutableStateOf(items.associate { it.itemId to (it.goal ?: 0) }) }
    Column(Modifier.padding(top = 10.dp)) {
        Label("habits", "times next week")
        items.filter { it.kind == FocusKind.HABIT }.forEach { p ->
            val g = goals.value[p.itemId] ?: 0
            ItemRow("", AnnotatedString(p.title), onTitle = { onPick(p, g) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("[−]", style = t.small, modifier = Modifier.clickable { goals.value = goals.value + (p.itemId to maxOf(1, g - 1)) })
                    BasicText(" $g ", style = t.small.copy(color = Fern.hi))
                    BasicText("[+]", style = t.small, modifier = Modifier.clickable { goals.value = goals.value + (p.itemId to minOf(7, g + 1)) })
                    BasicText("  ${p.note}", style = t.label)
                }
            }
        }
        Label("tasks", "done on time")
        items.filter { it.kind == FocusKind.TASK }.forEach { p -> ItemRow("", AnnotatedString(p.title), onTitle = { onPick(p, null) }) { BasicText(p.note, style = t.label) } }
        if (items.isEmpty()) BasicText("nothing to pick yet: add a habit or a task first.", style = t.small)
        BasicText("click one to make it a focus · [cancel]", style = t.label, modifier = Modifier.padding(top = 8.dp).clickable(onClick = onCancel))
    }
}
