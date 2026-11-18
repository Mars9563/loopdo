package app.loopdo.android.tasks

import app.loopdo.client.tasks.ListInfo
import app.loopdo.client.tasks.TasksViewModel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.loopdo.client.today.TaskItem
import app.loopdo.client.today.short
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** The next saturday after today (the weekend), as the menu offers it. */
fun weekendAfter(today: LocalDate): LocalDate = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))

/**
 * Locked habits-tasks-v1, screen 6: long-press a task. Move it to another day or list, open the
 * editor, or delete it (asks first: a deleted task can't come back). A late task moved to a new
 * date keeps its late history (the server records it).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TaskMenuSheet(t: TaskItem, today: LocalDate, lists: List<ListInfo>, vm: TasksViewModel, onEdit: () -> Unit, onClose: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val none = remember { MutableInteractionSource() }
    Popup(onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(none, indication = null, onClick = onClose).navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier.padding(14.dp).fillMaxWidth().background(Color(0xFA101826), shape)
                    .border(1.dp, Color(0x33BED7FF), shape).clickable(none, indication = null) {}.padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, style = LoopDoType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    t.lateSince?.let { Text("late since ${it.short().substringAfter(' ')}", style = LoopDoType.small.copy(color = LoopDoColors.warn)) }
                }
                fun act(block: () -> Unit) { block(); onClose() }
                if (!t.done) {
                    Text("MOVE TO", style = LoopDoType.label, modifier = Modifier.padding(top = 12.dp))
                    FlowRow {
                        val weekend = weekendAfter(today)
                        listOf("today" to today, "tomorrow" to today.plusDays(1), weekend.short() to weekend).forEach { (label, date) ->
                            Bracket(label, primary = t.due == date, idle = t.due != date) { act { vm.move(t.id, date) } }
                        }
                        Bracket("pick date", idle = true) { picking = true }
                        Bracket("no date", primary = t.due == null, idle = t.due != null) { act { vm.move(t.id, null) } }
                    }
                }
                Text("LIST", style = LoopDoType.label, modifier = Modifier.padding(top = 8.dp))
                FlowRow {
                    lists.forEach { l ->
                        val on = l.id == t.listId
                        Bracket("# ${l.name}", primary = on, idle = !on) { act { vm.moveToList(t.id, l.id, l.name) } }
                    }
                }
                DashedRule(Modifier.padding(vertical = 8.dp))
                BracketRow(
                    *listOfNotNull(
                        BracketItem("edit ↗") { act(onEdit) },
                        // A done task stays in the history (review of M5): no delete.
                        BracketItem("delete", danger = true) { deleting = true }.takeIf { !t.done },
                        BracketItem("close", idle = true, onClick = onClose),
                    ).toTypedArray(),
                )
            }
        }
    }
    if (picking) {
        val first = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (t.due?.takeIf { it >= today } ?: today.plusDays(2)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= first
                override fun isSelectableYear(year: Int) = year >= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                Bracket("ok", primary = true) {
                    state.selectedDateMillis?.let { vm.move(t.id, Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false; onClose()
                }
            },
            dismissButton = { Bracket("cancel", idle = true) { picking = false } },
        ) { DatePicker(state) }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        containerColor = Color(0xF51E283A),
        title = { Text("delete \"${t.title}\"?", style = LoopDoType.title) },
        text = { Text("it can't be brought back.", style = LoopDoType.body.copy(color = LoopDoColors.dim)) },
        confirmButton = { Bracket("delete", danger = true) { deleting = false; vm.delete(t.id); onClose() } },
        dismissButton = { Bracket("cancel", idle = true) { deleting = false } },
    )
}
