package app.loopdo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.loopdo.client.habits.EditorStart
import app.loopdo.client.habits.HabitDraft
import app.loopdo.client.habits.checkDraft
import app.loopdo.client.habits.targetChangeNote
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** A `[choice]`: mint when picked, lichen otherwise; dim and inert when [enabled] is false. */
@Composable
fun Choice(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalType.current
    BasicText("[$label]", style = t.body.copy(color = when { on -> Fern.mint; enabled -> Fern.lichen; else -> Fern.moss }),
        modifier = Modifier.padding(end = 14.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier))
}

/** The right-hand sheet over a dimmed screen (locked G/H); esc closes it, never the window. */
@Composable
fun Sheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val t = LocalType.current
    // A click outside closes it, with the mouse only: `clickable` also takes Space/Enter bubbling up from a field
    // inside as a click, so a typed space closed the sheet (owner, 2026-11-19).
    Box(Modifier.fillMaxSize().background(Color(0x73050810)).pointerInput(onClose) { detectTapGestures { onClose() } }) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(430.dp).fillMaxHeight().background(Color(0xFC090F18)).dashedLeft()
                .pointerInput(Unit) { detectTapGestures { } }.onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onClose(); true } else false }
                .padding(horizontal = 26.dp, vertical = 22.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth()) {
                BasicText(title, style = t.body.copy(color = Fern.hi))
                Spacer(Modifier.weight(1f))
                BasicText("[esc]", style = t.small, modifier = Modifier.clickable(onClick = onClose))
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

fun Modifier.dashedLeft(color: Color = Fern.rule) = this.drawBehind {
    var y = 0f
    while (y < size.height) { drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(0f, minOf(y + 3f, size.height))); y += 6f }
}

@Composable
private fun FieldLabel(s: String) = BasicText(s, style = LocalType.current.label, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

/**
 * The habit editor (locked desktop-screens-v1 G) with the phone's rules: kind and target lock once the habit has a
 * tick (past days are judged as they were); a schedule change starts tomorrow; `checkDraft`'s messages show here.
 */
@Composable
fun HabitEditor(
    start: EditorStart?, today: LocalDate, archived: Boolean,
    onSave: (HabitDraft, (String) -> Unit) -> Unit, onArchive: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit,
) {
    val t = LocalType.current
    val d0 = start?.draft ?: HabitDraft("", HabitKind.YES_NO, 1, null, 1, Recurrence.DAILY, emptySet(), null)
    val locked = start?.hasHistory == true
    var title by remember { mutableStateOf(d0.title) }
    var kind by remember { mutableStateOf(d0.kind) }
    var target by remember { mutableStateOf(d0.target.toString()) }
    var unit by remember { mutableStateOf(d0.unit.orEmpty()) }
    var step by remember { mutableStateOf(d0.step.toString()) }
    var rec by remember { mutableStateOf(if (d0.recurrence == Recurrence.PAUSED) Recurrence.DAILY else d0.recurrence) }
    var days by remember { mutableStateOf(d0.weekdays) }
    var reminder by remember { mutableStateOf(d0.reminderTime?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun draft(): HabitDraft {
        val time = reminder.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(if (it.length == 4) "0$it" else it) }.getOrElse { throw IllegalArgumentException("write the reminder as hh:mm, e.g. 07:30.") } }
        val n = { s: String, what: String -> s.trim().toIntOrNull() ?: throw IllegalArgumentException("$what needs a number.") }
        return checkDraft(HabitDraft(title, kind, if (kind == HabitKind.COUNT) n(target, "the target") else 1, unit.takeIf { kind == HabitKind.COUNT }, if (kind == HabitKind.COUNT) n(step, "the step") else 1, rec, if (rec == Recurrence.WEEKDAYS) days else emptySet(), time))
    }
    // One save at a time: Enter again (or held) while it runs created the habit twice (ultrareview; the phone's `saving`).
    var saving by remember { mutableStateOf(false) }
    val save = {
        if (!saving) runCatching { draft() }.onSuccess { saving = true; onSave(it) { e -> saving = false; error = e } }.onFailure { error = it.message }
    }
    Sheet(if (start == null) "~/habits/new" else "~/habits/${d0.title}", onClose) {
        val nameFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
        FieldLabel("name")
        TermField(title, { title = it; error = null }, Modifier.focusRequester(nameFocus), onEnter = { save() })
        FieldLabel("kind")
        Row { Choice("yes/no", kind == HabitKind.YES_NO, !locked) { kind = HabitKind.YES_NO }; Choice("count", kind == HabitKind.COUNT, !locked) { kind = HabitKind.COUNT } }
        if (kind == HabitKind.COUNT) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { FieldLabel("target a day"); TermField(target, { target = it; error = null }, onEnter = { save() }) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { FieldLabel("unit"); TermField(unit, { unit = it }, onEnter = { save() }) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { FieldLabel("[+n] adds"); TermField(step, { step = it; error = null }, onEnter = { save() }) }
            }
            start?.let { s -> target.toIntOrNull()?.let { targetChangeNote(s.todayTarget, it, today) }?.let { BasicText(it, style = t.label, modifier = Modifier.padding(top = 6.dp)) } }
        }
        FieldLabel("days")
        Row { Choice("every day", rec == Recurrence.DAILY) { rec = Recurrence.DAILY }; Choice("weekdays", rec == Recurrence.WEEKDAYS) { rec = Recurrence.WEEKDAYS } }
        if (rec == Recurrence.WEEKDAYS) Row(Modifier.padding(top = 6.dp)) {
            DayOfWeek.entries.forEach { dw -> Choice(dw.name.take(2).lowercase(), dw in days) { days = if (dw in days) days - dw else days + dw } }
        }
        FieldLabel("reminder (hh:mm, empty = none)")
        TermField(reminder, { reminder = it; error = null }, onEnter = { save() })
        BasicText(
            if (locked) "it has ticks, so its kind stays. changes start tomorrow; past days keep their rules." else "starts today. kind and target lock once you tick it once.",
            style = t.label.copy(lineHeight = t.label.fontSize * 1.6), modifier = Modifier.padding(top = 14.dp),
        )
        error?.let { BasicText(it, style = t.small.copy(color = Fern.ember), modifier = Modifier.padding(top = 10.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("[save ↵]", style = t.body.copy(color = Fern.mint), modifier = Modifier.clickable { save() })
            Spacer(Modifier.weight(1f))
            if (start != null && !archived) BasicText("[archive]", style = t.small, modifier = Modifier.padding(end = 14.dp).clickable(onClick = onArchive))
            if (start != null) BasicText(if (confirmDelete) "[sure? delete]" else "[delete]", style = t.small.copy(color = Fern.ember),
                modifier = Modifier.clickable { if (confirmDelete) onDelete() else confirmDelete = true })
        }
    }
}
