package app.loopdo.android.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private enum class When { TODAY, TOMORROW, PICKED, NONE }

/** The widget's `[+ add]` card (locked widget-v4, pop-up 1), over the dimmed app. Repeat comes with M4. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(
    today: LocalDate,
    lists: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onAddTask: (title: String, due: LocalDate?, time: LocalTime?, listId: String?, listName: String?) -> Unit,
    onAddJournal: (String) -> Unit,
) {
    var journal by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var whenChoice by rememberSaveable { mutableStateOf(When.TODAY) }
    var picked by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var time by rememberSaveable { mutableStateOf<LocalTime?>(null) }
    var listId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var pickingList by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { focus.requestFocus() }

    val due = when (whenChoice) {
        When.TODAY -> today
        When.TOMORROW -> today.plusDays(1)
        When.PICKED -> picked
        When.NONE -> null
    }
    val canAdd = text.isNotBlank()
    fun submit() {
        if (!canAdd) return
        if (journal) onAddJournal(text) else onAddTask(text, due, time.takeIf { due != null }, listId, lists.firstOrNull { it.first == listId }?.second)
    }

    val none = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(none, indication = null, onClick = onDismiss)
            .navigationBarsPadding().imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(14.dp).fillMaxWidth().shadow(20.dp, shape)
                .background(Brush.linearGradient(listOf(Color(0xEB162032), Color(0xEB080C14))), shape)
                .border(1.dp, Color(0x33BED7FF), shape)
                .clickable(none, indication = null) {} // taps on the card don't close it
                .padding(16.dp),
        ) {
            BracketRow(
                BracketItem("task", primary = !journal, idle = journal) { journal = false },
                BracketItem("journal line", primary = journal, idle = !journal) { journal = true },
            )
            DashedRule(Modifier.padding(bottom = 10.dp))
            Box(
                Modifier.fillMaxWidth().background(Color(0x40000000), RoundedCornerShape(12.dp))
                    .border(1.dp, LoopDoColors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) Text(
                    if (journal) "a line for today's entry" else "what needs doing?",
                    style = LoopDoType.body.copy(color = LoopDoColors.done),
                )
                BasicTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    textStyle = LoopDoType.body, cursorBrush = SolidColor(LoopDoColors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (!journal) {
                Text("WHEN", style = LoopDoType.label, modifier = Modifier.padding(top = 12.dp))
                FlowRow {
                    Chip("today", whenChoice == When.TODAY) { whenChoice = When.TODAY }
                    Chip("tomorrow", whenChoice == When.TOMORROW) { whenChoice = When.TOMORROW }
                    Chip(picked?.takeIf { whenChoice == When.PICKED }?.short() ?: "pick date", whenChoice == When.PICKED) { pickingDate = true }
                    Chip("no date", whenChoice == When.NONE) { whenChoice = When.NONE; time = null }
                }
                Text("EXTRAS", style = LoopDoType.label, modifier = Modifier.padding(top = 4.dp))
                FlowRow {
                    // A time needs a date; tapping a set time clears it.
                    Chip(time?.let { "@ $it" } ?: "@ time", time != null, enabled = due != null) { if (time != null) time = null else pickingTime = true }
                    Box {
                        Chip("# " + (lists.firstOrNull { it.first == listId }?.second ?: "inbox"), listId != null) { pickingList = true }
                        DropdownMenu(pickingList, onDismissRequest = { pickingList = false }) {
                            (listOf(null to "inbox") + lists).forEach { (id, name) ->
                                DropdownMenuItem(text = { Text("# $name", style = LoopDoType.body) }, onClick = { listId = id; pickingList = false })
                            }
                        }
                    }
                }
            }
            BracketRow(
                BracketItem("cancel", idle = true, onClick = onDismiss),
                BracketItem("add ↵", primary = true, enabled = canAdd) { submit() },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (pickingDate) {
        val todayMs = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (picked ?: today.plusDays(2)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayMs
                override fun isSelectableYear(year: Int) = year >= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                Bracket("ok", primary = true) {
                    state.selectedDateMillis?.let {
                        picked = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        whenChoice = When.PICKED
                    }
                    pickingDate = false
                }
            },
            dismissButton = { Bracket("cancel", idle = true) { pickingDate = false } },
        ) { DatePicker(state) }
    }
    if (pickingTime) {
        val state = rememberTimePickerState(initialHour = time?.hour ?: 18, initialMinute = time?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = { Bracket("ok", primary = true) { time = LocalTime.of(state.hour, state.minute); pickingTime = false } },
            dismissButton = { Bracket("cancel", idle = true) { pickingTime = false } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun Chip(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    Bracket(label, primary = on, idle = !on, enabled = enabled, onClick = onClick)
