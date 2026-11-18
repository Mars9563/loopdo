package app.loopdo.android.stats

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.shared.stats.Cell
import app.loopdo.shared.stats.FocusKind
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Locked weekly-review-v1, screen A (and B, the picker, on top). */
@Composable
internal fun ReviewScreen(r: ReviewUi, vm: StatsViewModel) {
    val message by vm.message.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    val w = r.review
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Bracket("<", idle = true) { vm.closeReview() }
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = LoopDoColors.accent)) { append("~/stats/") }
                    append("review")
                }, style = LoopDoType.title, modifier = Modifier.weight(1f))
                Bracket("< ${w.number - 1}", idle = true) { vm.page(-1) }
                if (r.canGoForward) Bracket("${w.number + 1} >", idle = true) { vm.page(1) }
            }
            Text(buildAnnotatedString {
                append(weekTitle(w))
                if (r.isLastDay) withStyle(SpanStyle(color = LoopDoColors.accent)) { append(" · today is its last day") }
            }, style = LoopDoType.small)
            DashedRule(Modifier.padding(vertical = 6.dp))
            SectionLabel("how it went")
            KeyValue("done") { Text(doneLine(w, vsWeek = true), style = LoopDoType.small) }
            KeyValue("days") {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = LoopDoColors.accent)) { append("perfect ") }
                    withStyle(SpanStyle(color = LoopDoColors.bright)) { append("${w.perfectDays}") }
                    withStyle(SpanStyle(color = LoopDoColors.dim)) { append(" · ") }
                    withStyle(SpanStyle(color = LoopDoColors.accent)) { append("active ") }
                    withStyle(SpanStyle(color = LoopDoColors.bright)) { append("${w.activeDays}/7") }
                }, style = LoopDoType.small)
            }
            WeekGrid(r)
            KeyValue("tasks") {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = LoopDoColors.bright)) { append("${w.tasksDone}") }
                    append(" done")
                    if (w.late.isNotEmpty()) { withStyle(SpanStyle(color = LoopDoColors.dim)) { append(" · ") }; withStyle(SpanStyle(color = LoopDoColors.warn)) { append("${w.late.size} late") } }
                }, style = LoopDoType.small)
            }
            if (w.late.isNotEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 22.dp)) {
                Text("  late", style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp), modifier = Modifier.weight(1f))
                Text(w.late.joinToString(" · ") { "${it.title} (${it.daysLate}d)" }, style = LoopDoType.small.copy(color = LoopDoColors.warn, fontSize = 11.sp), textAlign = TextAlign.End)
            }
            DashedRule(Modifier.padding(vertical = 6.dp))
            SectionLabel("last week you said") { if (r.lastWeekSaid.isNotEmpty()) Text("${r.kept}/${r.lastWeekSaid.size} kept", style = LoopDoType.label) }
            if (r.lastWeekSaid.isEmpty()) Text("no focus was set for this week.", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            r.lastWeekSaid.forEach { line ->
                val (left, right) = line.split("  →  ").let { it[0] to it.getOrElse(1) { "" } }
                KeyValue(left) {
                    Text(right, style = LoopDoType.small.copy(color = when {
                        right.endsWith("✓") -> LoopDoColors.accent
                        "missed" in right || "late" in right || right == "not done" -> LoopDoColors.warn
                        else -> LoopDoColors.dim
                    }))
                }
            }
            DashedRule(Modifier.padding(vertical = 6.dp))
            val end = r.nextWeek.plusDays(6)
            SectionLabel("next week · ${r.nextWeek.dayOfMonth}–${end.format(dayMonth).lowercase()}") { Text(r.nextNote, style = LoopDoType.label) }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .border(1.dp, LoopDoColors.accent.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                    .background(LoopDoColors.accent.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                r.next.forEach { f ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("› ", style = LoopDoType.small.copy(color = LoopDoColors.accent))
                        Text(f.title, style = LoopDoType.small, modifier = Modifier.weight(1f))
                        Text(if (f.kind == FocusKind.HABIT) "${f.goal}×" else "by ${f.deadline?.dayOfWeek?.name?.take(3)?.lowercase()}", style = LoopDoType.small)
                        if (r.canEdit) Bracket("×", idle = true) { vm.removeFocus(f.id) }
                    }
                }
                if (r.canEdit && r.next.size < 2) Bracket("+ focus", primary = true) { picking = true }
                if (r.next.isEmpty() && !r.canEdit) Text("the focus can be set on sunday or monday.", style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(vertical = 8.dp))
            }
            message?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn), modifier = Modifier.padding(vertical = 6.dp)) }
            Spacer(Modifier.height(24.dp))
        }
        if (picking) FocusSheet(r, onPick = { item, goal -> vm.addFocus(item, goal); picking = false }, onCancel = { picking = false })
    }
}

@Composable
private fun WeekGrid(r: ReviewUi) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(96.dp))
            "mtwtfss".forEach { Text(it.toString(), style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 10.sp), modifier = Modifier.weight(1f), textAlign = TextAlign.Center) }
            Spacer(Modifier.width(44.dp))
        }
        r.review.habits.forEach { h ->
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(buildAnnotatedString {
                    if (h.id in r.focusIds) withStyle(SpanStyle(color = LoopDoColors.accent)) { append("› ") }
                    append(h.title)
                }, style = LoopDoType.small, modifier = Modifier.width(96.dp), maxLines = 1)
                h.cells.forEach { c ->
                    val (glyph, color) = when (c) {
                        Cell.DONE -> "█" to LoopDoColors.accent
                        Cell.MISSED -> "▢" to LoopDoColors.warn
                        Cell.FROZEN -> "❄" to LoopDoColors.accent
                        Cell.NOT_DUE -> "·" to LoopDoColors.separator
                        Cell.OPEN -> "○" to LoopDoColors.dim
                        Cell.FUTURE -> "" to LoopDoColors.dim
                    }
                    Text(glyph, style = LoopDoType.small.copy(color = color), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
                Text("${h.done}/${h.due}", style = LoopDoType.small.copy(color = if (h.done < h.due) LoopDoColors.warn else LoopDoColors.text),
                    modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
            }
        }
        Text("█ done · ▢ missed · ❄ freeze · · not due · ○ still open today", style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 10.sp))
    }
}
