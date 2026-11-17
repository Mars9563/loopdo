package app.loopdo.android.tasks

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.habits.short as dayShort
import app.loopdo.android.today.short
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.android.ui.theme.TerminalField
import app.loopdo.shared.task.RepeatRule
import app.loopdo.shared.task.parseRepeatRule
import app.loopdo.shared.task.toJson
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Locked habits-tasks-v1, screen 5: one screen for a task's when, list, repeat, checklist and
 * notes. An occurrence of a series asks "this one" or "this and future" on [save]; a series
 * opened from the REPEATING section edits the series and offers [stop repeating]. Typed text is
 * kept across rotation and refusals.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorScreen(taskId: String, today: LocalDate, vm: TasksViewModel, onClose: () -> Unit, onOpenLists: () -> Unit) {
    val task by remember(taskId) { vm.task(taskId) }.collectAsStateWithLifecycle(null)
    val t = task ?: return
    val series by remember(t.templateId) { t.templateId?.let(vm::task) ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsStateWithLifecycle(null)
    val lists by vm.lists.collectAsStateWithLifecycle()
    val checklist by remember(taskId) { vm.subtasks(taskId) }.collectAsStateWithLifecycle(emptyList())
    val seriesRule = t.repeat ?: series?.repeat

    var title by rememberSaveable(taskId) { mutableStateOf(t.title) }
    var notes by rememberSaveable(taskId) { mutableStateOf(t.notes.orEmpty()) }
    var due by rememberSaveable(taskId) { mutableStateOf(t.due?.toString()) }
    var time by rememberSaveable(taskId) { mutableStateOf(t.time?.toString()) }
    var listId by rememberSaveable(taskId) { mutableStateOf(t.listId) }
    var repeat by rememberSaveable(taskId) { mutableStateOf(seriesRule?.toJson()) }
    var error by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var newItem by rememberSaveable(taskId) { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    // The series' rule may load after the task (an occurrence): start from it once it is known.
    LaunchedEffect(series?.repeat) { if (repeat == null && series?.repeat != null) repeat = series?.repeat?.toJson() }

    val rule = repeat?.let(::parseRepeatRule)
    val dueDate = due?.let(LocalDate::parse)
    val draft = TaskDraft(title, notes, dueDate, time?.let(LocalTime::parse), listId?.let(UUID::fromString), rule)
    val scope = saveScope(t.isTemplate, t.isInstance)
    fun save(s: SaveScope) = vm.save(t.id, if (t.isTemplate) t.id else t.templateId, draft, s, onSaved = onClose, onError = { error = it })

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Bracket("<", idle = true, onClick = onClose)
            Text("~/tasks/${t.title}", style = LoopDoType.title.copy(color = LoopDoColors.accent), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // The server's record once a late task was moved; else its own due date while it is late now.
            (t.lateSince ?: t.due?.takeIf { it < today && t.completedOn == null && !t.isTemplate })?.let {
                Text("late since ${it.short().substringAfter(' ')}", style = LoopDoType.small.copy(color = LoopDoColors.warn))
            }
        }
        TerminalField(if (t.isTemplate) "repeating task" else "task", title, { title = it; error = null })

        Label("when")
        FlowRow {
            val choices = listOf("today" to today, "tomorrow" to today.plusDays(1))
            choices.forEach { (label, d) -> Chip(label, dueDate == d) { due = d.toString() } }
            val picked = dueDate?.takeIf { d -> choices.none { it.second == d } }
            Chip(picked?.short() ?: "pick date", picked != null) { pickingDate = true }
            if (!t.isTemplate) Chip("no date", dueDate == null) { due = null; time = null }
        }
        Chip(time?.let { "@ $it" } ?: "@ time", time != null, enabled = dueDate != null) { if (time != null) time = null else pickingTime = true }

        Label("list")
        FlowRow {
            lists.forEach { l -> Chip("# ${l.name}", l.id == listId) { listId = l.id } }
            Chip("+ list", false, onClick = onOpenLists)
        }

        Label("repeat")
        FlowRow {
            Chip("never", rule == null) { repeat = null }
            Chip("daily", rule == RepeatRule.Daily) { repeat = RepeatRule.Daily.toJson() }
            Chip("weekly", rule is RepeatRule.Weekly) { repeat = RepeatRule.Weekly(setOf((dueDate ?: today).dayOfWeek)).toJson() }
            Chip("monthly", rule is RepeatRule.MonthlyOnDay) { repeat = RepeatRule.MonthlyOnDay((dueDate ?: today).dayOfMonth).toJson() }
            Chip("every n days", rule is RepeatRule.EveryNDays) { repeat = RepeatRule.EveryNDays(2, dueDate ?: today).toJson() }
        }
        rule?.let { RepeatDetails(it, from = maxOf(dueDate ?: today, today)) { r -> repeat = r.toJson() } }

        if (!t.isTemplate) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
                Text("CHECKLIST", style = LoopDoType.label, modifier = Modifier.weight(1f))
                if (checklist.isNotEmpty()) Text("${checklist.count { it.done }}/${checklist.size}", style = LoopDoType.label)
            }
        } else Label("checklist (each new one gets it)")
        checklist.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Bracket(if (s.done) "x" else " ", idle = s.done) { vm.toggleSubtask(s.id) }
                Text(s.title, style = LoopDoType.body.copy(color = if (s.done) LoopDoColors.done else LoopDoColors.text), modifier = Modifier.weight(1f))
                Bracket("−", idle = true) { vm.deleteSubtask(s.id) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallInput(newItem, { newItem = it }, "+ item", Modifier.weight(1f))
            Bracket("add", primary = newItem.isNotBlank(), enabled = newItem.isNotBlank()) {
                vm.addSubtask(t.id, newItem, onDone = { newItem = "" }, onError = { error = it })
            }
        }

        Label("notes")
        SmallInput(notes, { notes = it; error = null }, "", Modifier.fillMaxWidth().heightIn(min = 48.dp), singleLine = false)

        error?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 10.dp)) }
        if (t.isTemplate) BracketRow(
            BracketItem("stop repeating", danger = true) { vm.stopRepeating(t.id, onClose) },
            BracketItem("save", primary = true) { save(SaveScope.SERIES) },
            modifier = Modifier.padding(top = 10.dp),
        ) else BracketRow(
            BracketItem("delete", danger = true) { deleting = true },
            BracketItem("save", primary = true) { if (scope == SaveScope.ASK) asking = true else save(scope) },
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(40.dp))
    }

    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        containerColor = Color(0xF51E283A),
        title = { Text("change which ones?", style = LoopDoType.title) },
        text = { Text("this one only, or this one and the ones after it (done and past ones stay as they were).", style = LoopDoType.body.copy(color = LoopDoColors.dim)) },
        confirmButton = { Bracket("this and future", primary = true) { asking = false; save(SaveScope.SERIES) } },
        dismissButton = { Bracket("this one", idle = true) { asking = false; save(SaveScope.ONE) } },
    )
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        containerColor = Color(0xF51E283A),
        title = { Text("delete \"${t.title}\"?", style = LoopDoType.title) },
        text = { Text("it can't be brought back.", style = LoopDoType.body.copy(color = LoopDoColors.dim)) },
        confirmButton = { Bracket("delete", danger = true) { deleting = false; vm.delete(t.id, onClose) } },
        dismissButton = { Bracket("cancel", idle = true) { deleting = false } },
    )
    if (pickingDate) DatePick(dueDate ?: today, today, onPick = { due = it.toString() }, onClose = { pickingDate = false })
    if (pickingTime) TimePick(time?.let(LocalTime::parse), onPick = { time = it.toString() }, onClose = { pickingTime = false })
}

/** Under a repeat choice: the weekday row (weekly), the day of month, or n; then the next two dates. */
@Composable
fun RepeatDetails(rule: RepeatRule, from: LocalDate, showNext: Boolean = true, onRule: (RepeatRule) -> Unit) {
    when (rule) {
        is RepeatRule.Weekly -> Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { d ->
                val on = d in rule.days
                Bracket(d.dayShort().take(1), primary = on, idle = !on, modifier = Modifier.weight(1f)) {
                    val days = if (on) rule.days - d else rule.days + d
                    if (days.isNotEmpty()) onRule(RepeatRule.Weekly(days))
                }
            }
        }
        is RepeatRule.MonthlyOnDay -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("on day", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            Bracket("−", idle = true, enabled = rule.day > 1) { onRule(RepeatRule.MonthlyOnDay(rule.day - 1)) }
            Text("${rule.day}", style = LoopDoType.body)
            Bracket("+", idle = true, enabled = rule.day < 31) { onRule(RepeatRule.MonthlyOnDay(rule.day + 1)) }
            Text("of each month", style = LoopDoType.small.copy(color = LoopDoColors.dim))
        }
        is RepeatRule.EveryNDays -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("every", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            Bracket("−", idle = true, enabled = rule.n > 2) { onRule(RepeatRule.EveryNDays(rule.n - 1, rule.anchor)) }
            Text("${rule.n}", style = LoopDoType.body)
            Bracket("+", idle = true, enabled = rule.n < 365) { onRule(RepeatRule.EveryNDays(rule.n + 1, rule.anchor)) }
            Text("days", style = LoopDoType.small.copy(color = LoopDoColors.dim))
        }
        RepeatRule.Daily -> Unit
    }
    if (showNext) Text("· " + nextLine(rule, from), style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = LoopDoType.label, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))

@Composable
private fun Chip(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    Bracket(label, primary = on, idle = !on, enabled = enabled, onClick = onClick)

@Composable
private fun SmallInput(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier, singleLine: Boolean = true) {
    Box(
        modifier.border(1.dp, LoopDoColors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = LoopDoType.body.copy(color = LoopDoColors.done))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            textStyle = LoopDoType.body, cursorBrush = SolidColor(LoopDoColors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePick(initial: LocalDate, earliest: LocalDate, onPick: (LocalDate) -> Unit, onClose: () -> Unit) {
    val first = earliest.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = maxOf(initial, earliest).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= first
            override fun isSelectableYear(year: Int) = year >= earliest.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Bracket("ok", primary = true) {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onClose()
            }
        },
        dismissButton = { Bracket("cancel", idle = true, onClick = onClose) },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePick(initial: LocalTime?, onPick: (LocalTime) -> Unit, onClose: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial?.hour ?: 18, initialMinute = initial?.minute ?: 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { Bracket("ok", primary = true) { onPick(LocalTime.of(state.hour, state.minute)); onClose() } },
        dismissButton = { Bracket("cancel", idle = true, onClick = onClose) },
        text = { TimePicker(state) },
    )
}

