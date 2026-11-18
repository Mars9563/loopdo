package app.loopdo.android.habits

import app.loopdo.client.habits.HabitsViewModel

import app.loopdo.client.habits.short

import app.loopdo.client.habits.EditorStart
import app.loopdo.client.habits.HabitDraft
import app.loopdo.client.habits.targetChangeNote

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.loopdo.client.today.short
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.android.ui.theme.TerminalField
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Locked habits-tasks-v1, screens 3 (new) and 4 (edit), plus the owner-approved additions:
 * "paused until <date>" with [end pause] / [cancel pause]. [start] = what the editor opened with
 * (kept while typing); [live] = the habit now (a pause ended elsewhere shows at once).
 * Every field is saved across rotation, and a refused save keeps it all.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HabitEditorScreen(
    habitId: String?,
    start: EditorStart?,
    live: EditorStart?,
    today: LocalDate,
    vm: HabitsViewModel,
    onClose: () -> Unit,
    onArchived: () -> Unit,
) {
    val isNew = habitId == null
    val s = start?.draft
    var title by rememberSaveable { mutableStateOf(s?.title ?: "") }
    var count by rememberSaveable { mutableStateOf(s?.kind == HabitKind.COUNT) }
    var target by rememberSaveable { mutableStateOf(s?.target?.takeIf { s.kind == HabitKind.COUNT }?.toString() ?: "") }
    var unit by rememberSaveable { mutableStateOf(s?.unit ?: "") }
    var step by rememberSaveable { mutableStateOf(s?.step ?: 1) }
    var weekly by rememberSaveable { mutableStateOf(s?.recurrence == Recurrence.WEEKDAYS) }
    var days by rememberSaveable { mutableStateOf(s?.weekdays?.map { it.value }?.toSet() ?: emptySet()) }
    var reminder by rememberSaveable { mutableStateOf(s?.reminderTime?.toString()) }
    /** The picked pause, as "from/to" ISO dates (rotation-safe), or null. */
    var pause by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var pickingDates by rememberSaveable { mutableStateOf(false) }

    val tomorrow = today.plusDays(1)
    val pauseRange = pause?.split("/")?.let { LocalDate.parse(it[0])..LocalDate.parse(it[1]) }
    val draft = HabitDraft(
        title = title, kind = if (count) HabitKind.COUNT else HabitKind.YES_NO,
        target = if (count) target.toIntOrNull() ?: 0 else 1, unit = unit, step = step,
        recurrence = if (weekly) Recurrence.WEEKDAYS else Recurrence.DAILY,
        weekdays = days.map(DayOfWeek::of).toSet(), reminderTime = reminder?.let(LocalTime::parse),
    )
    val typeLocked = !isNew && start?.hasHistory == true
    val id = habitId?.let(UUID::fromString)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Bracket("<", idle = true, onClick = onClose)
            Text(
                if (isNew) "~/habits/new" else "~/habits/${s?.title}/edit",
                style = LoopDoType.title.copy(color = LoopDoColors.accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TerminalField("name", title, { title = it; error = null })

        Label("type")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Bracket("yes / no", primary = !count, idle = count, enabled = !typeLocked || !count) { count = false; error = null }
            Text("|", style = LoopDoType.control.copy(color = LoopDoColors.separator))
            Bracket("count", primary = count, idle = !count, enabled = !typeLocked || count) { count = true; error = null }
        }

        if (count) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Text("TARGET", style = LoopDoType.label, modifier = Modifier.weight(1f))
                Text("STEP", style = LoopDoType.label)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SmallField(target, { target = it.filter(Char::isDigit).take(5); error = null }, 64.dp, KeyboardType.Number)
                Spacer(Modifier.width(8.dp))
                SmallField(unit, { unit = it.take(16); error = null }, 96.dp, placeholder = "unit")
                Spacer(Modifier.weight(1f))
                (listOf(1, 5, 10) + listOfNotNull(step.takeIf { it !in setOf(1, 5, 10) })).sorted().forEach { n ->
                    Bracket("+$n", primary = step == n, idle = step != n) { step = n }
                }
            }
            if (!isNew && start != null) target.toIntOrNull()?.let { new -> targetChangeNote(start.todayTarget, new, today) }?.let {
                NoteText(it, Modifier.padding(top = 6.dp))
            }
        }

        Label("days")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Bracket("every day", primary = !weekly, idle = weekly) { weekly = false; error = null }
            Text("|", style = LoopDoType.control.copy(color = LoopDoColors.separator))
            Bracket("pick days", primary = weekly, idle = !weekly) { weekly = true; error = null }
        }
        if (weekly) Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { day ->
                val on = day.value in days
                Bracket(day.short().take(1), primary = on, idle = !on, modifier = Modifier.weight(1f)) {
                    days = if (on) days - day.value else days + day.value; error = null
                }
            }
        }
        if (!isNew && start != null && (weekly != (start.draft.recurrence == Recurrence.WEEKDAYS) || (weekly && days != start.draft.weekdays.map { it.value }.toSet()))) {
            NoteText("new days apply from tomorrow, ${tomorrow.short()}. past days keep the days they had.", Modifier.padding(top = 4.dp))
        }

        Label("remind me")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Bracket(reminder?.let { "@ $it" } ?: "@ time", primary = reminder != null, idle = reminder == null) { pickingTime = true }
            Bracket("no reminder", primary = reminder == null, idle = reminder != null) { reminder = null }
        }

        if (isNew) {
            Text(
                "starts today, ${today.short()}. its streak starts at 0; ❄ freezes are earned every 7 days, max 2.",
                style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(top = 14.dp),
            )
        } else {
            DashedRule(Modifier.padding(top = 14.dp, bottom = 4.dp))
            val p = live?.pause
            when {
                p != null && p.started -> {
                    Text("paused until ${p.until?.short() ?: "you end it"}.", style = LoopDoType.body.copy(color = LoopDoColors.accent), modifier = Modifier.padding(top = 8.dp))
                    Bracket("end pause", primary = true) { id?.let(vm::endPause) }
                }
                p != null -> {
                    Text("paused ${p.from.short()} – ${p.until?.short() ?: "…"}.", style = LoopDoType.body.copy(color = LoopDoColors.accent), modifier = Modifier.padding(top = 8.dp))
                    Bracket("cancel pause", primary = true) { id?.let(vm::cancelPause) }
                }
                else -> {
                    Label("pause (travel, exams, sick)")
                    FlowRow {
                        listOf("3 days" to 3L, "1 week" to 7L, "2 weeks" to 14L).forEach { (label, n) ->
                            val range = "$tomorrow/${tomorrow.plusDays(n - 1)}"
                            Bracket(label, primary = pause == range, idle = pause != range) { pause = if (pause == range) null else range }
                        }
                        val picked = pause != null && pause !in listOf(3L, 7L, 14L).map { "$tomorrow/${tomorrow.plusDays(it - 1)}" }
                        Bracket("pick dates", primary = picked, idle = !picked) { pickingDates = true }
                    }
                    if (pauseRange != null) NoteText(
                        "paused ${pauseRange.start.short()} – ${pauseRange.endInclusive.short()}. due again ${pauseRange.endInclusive.plusDays(1).short()}; it starts when you [save].",
                        Modifier.padding(top = 4.dp),
                    ) else Text(
                        "a pause starts tomorrow at the earliest. paused days count as \"not due\": the streak waits, nothing breaks, nothing is gained.",
                        style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (typeLocked) Text(
                "type can't change once a habit has history (it would re-judge past days). make a new habit instead.",
                style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(top = 12.dp),
            )
        }

        error?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 10.dp)) }

        val save = {
            if (count && target.isBlank()) error = "type a target first."
            else {
                saving = true
                vm.save(id, draft, pauseRange, onSaved = { saving = false; onClose() }, onError = { saving = false; error = it })
            }
        }
        if (isNew) BracketRow(
            BracketItem("cancel", idle = true, onClick = onClose),
            BracketItem("create", primary = true, enabled = !saving) { save() },
            modifier = Modifier.padding(top = 10.dp),
        ) else BracketRow(
            BracketItem("archive", danger = true, enabled = !saving) { id?.let { vm.archive(it, onArchived) } },
            BracketItem("cancel", idle = true, onClick = onClose),
            BracketItem("save", primary = true, enabled = !saving) { save() },
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(40.dp))
    }

    if (pickingTime) {
        val t = reminder?.let(LocalTime::parse)
        val state = rememberTimePickerState(initialHour = t?.hour ?: 20, initialMinute = t?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = { Bracket("ok", primary = true) { reminder = LocalTime.of(state.hour, state.minute).toString(); pickingTime = false } },
            dismissButton = { Bracket("cancel", idle = true) { pickingTime = false } },
            text = { TimePicker(state) },
        )
    }
    if (pickingDates) {
        val first = tomorrow.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = pauseRange?.start?.utcMillis(),
            initialSelectedEndDateMillis = pauseRange?.endInclusive?.utcMillis(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= first
                override fun isSelectableYear(year: Int) = year >= tomorrow.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDates = false },
            confirmButton = {
                Bracket("ok", primary = true, enabled = state.selectedStartDateMillis != null) {
                    val a = state.selectedStartDateMillis?.utcDate()
                    val b = state.selectedEndDateMillis?.utcDate() ?: a
                    if (a != null && b != null) pause = "$a/$b"
                    pickingDates = false
                }
            },
            dismissButton = { Bracket("cancel", idle = true) { pickingDates = false } },
        ) { DateRangePicker(state, modifier = Modifier.height(480.dp)) }
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = LoopDoType.label, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))

/** A small boxed input (target, unit), like the name field. */
@Composable
private fun SmallField(value: String, onChange: (String) -> Unit, width: Dp, keyboard: KeyboardType = KeyboardType.Text, placeholder: String? = null) {
    Box(
        Modifier.width(width).height(48.dp)
            .border(1.dp, LoopDoColors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder != null) Text(placeholder, style = LoopDoType.body.copy(color = LoopDoColors.done))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = LoopDoType.body, cursorBrush = SolidColor(LoopDoColors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun LocalDate.utcMillis() = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
