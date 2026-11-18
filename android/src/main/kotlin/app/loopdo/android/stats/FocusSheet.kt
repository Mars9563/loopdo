package app.loopdo.android.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.shared.stats.FocusKind
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Locked weekly-review-v1, screen B: pick one item (habit: N times, task: done on time), then [save ↵]. */
@Composable
internal fun FocusSheet(r: ReviewUi, onPick: (PickItem, Int?) -> Unit, onCancel: () -> Unit) {
    var chosen by remember { mutableStateOf<PickItem?>(null) }
    var goal by remember { mutableStateOf(1) }
    val taken = r.next.map { it.itemId }.toSet()
    val end = r.nextWeek.plusDays(6)
    Box(Modifier.fillMaxSize().background(Color(0x99040810)).clickable(onClick = onCancel), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp)
                .background(Color(0xF70E1624), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .border(1.dp, LoopDoColors.accent.copy(alpha = 0.3f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .clickable(enabled = false) {}
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("~/focus ", style = LoopDoType.title.copy(color = LoopDoColors.accent))
                Text("${r.nextWeek.dayOfMonth}–${end.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)).lowercase()}", style = LoopDoType.title, modifier = Modifier.weight(1f))
                Text("${r.next.size + 1} of 2", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                SectionLabel("habits") { Text("times next week".uppercase(), style = LoopDoType.label) }
                r.picker.filter { it.kind == FocusKind.HABIT && it.itemId !in taken }.forEach { p ->
                    PickRow(p, chosen == p, { chosen = p; goal = p.goal ?: 1 }) {
                        if (chosen == p) {
                            Bracket("−", primary = true) { goal = (goal - 1).coerceAtLeast(1) }
                            Text("$goal", style = LoopDoType.small.copy(color = LoopDoColors.bright))
                            Bracket("+", primary = true) { goal = (goal + 1).coerceAtMost(7) }
                            Text("/${p.goal} due", style = LoopDoType.small.copy(color = LoopDoColors.dim))
                        } else Text(p.note, style = LoopDoType.small.copy(color = LoopDoColors.dim))
                    }
                }
                SectionLabel("tasks") { Text("done on time".uppercase(), style = LoopDoType.label) }
                r.picker.filter { it.kind == FocusKind.TASK && it.itemId !in taken }.forEach { p ->
                    PickRow(p, chosen == p, { chosen = p }) {
                        Text(p.note, style = LoopDoType.small.copy(color = if (p.note.startsWith("late")) LoopDoColors.warn else LoopDoColors.dim))
                    }
                }
            }
            DashedRule(Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Bracket("cancel", idle = true, onClick = onCancel)
                Box(Modifier.weight(1f))
                Bracket("save ↵", primary = true, enabled = chosen != null) { chosen?.let { onPick(it, if (it.kind == FocusKind.HABIT) goal else null) } }
            }
        }
    }
}

@Composable
private fun PickRow(p: PickItem, selected: Boolean, onSelect: () -> Unit, right: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "› " else "  ", style = LoopDoType.small.copy(color = LoopDoColors.accent))
        Text(p.title, style = LoopDoType.small.copy(color = if (selected) LoopDoColors.bright else LoopDoColors.text), modifier = Modifier.weight(1f), maxLines = 1)
        right()
    }
}
