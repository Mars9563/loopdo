package app.loopdo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.loopdo.client.tasks.ListInfo
import app.loopdo.client.tasks.SaveScope
import app.loopdo.client.tasks.Subtask
import app.loopdo.client.tasks.TaskDetail
import app.loopdo.client.tasks.TaskDraft
import app.loopdo.client.tasks.saveScope
import app.loopdo.client.today.short
import app.loopdo.shared.task.RepeatRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** What the task editor asks for: saving (new or existing, with the series scope), steps and lists. */
interface TaskEditorUi {
    fun saveNew(draft: TaskDraft, onSaved: () -> Unit, onError: (String) -> Unit)
    fun save(id: String, seriesId: String?, draft: TaskDraft, scope: SaveScope, onSaved: () -> Unit, onError: (String) -> Unit)
    fun delete(id: String, onDone: () -> Unit)
    fun stopRepeating(templateId: String, onDone: () -> Unit)
    fun addStep(taskId: String, title: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun toggleStep(id: String)
    fun deleteStep(id: String)
    fun newList(name: String, onDone: () -> Unit, onError: (String) -> Unit)
}

/** "2026-11-21", "21 nov", "tomorrow"… → a date (null = not understood). */
fun parseDay(s: String, today: LocalDate): LocalDate? {
    val v = s.trim().lowercase()
    if (v.isEmpty()) return null
    if (v == "today") return today
    if (v == "tomorrow") return today.plusDays(1)
    runCatching { return LocalDate.parse(v) }
    val m = Regex("""^(\d{1,2})\s+([a-z]{3})[a-z]*(?:\s+(\d{4}))?$""").matchEntire(v) ?: return null
    val month = java.time.Month.entries.firstOrNull { it.name.lowercase().startsWith(m.groupValues[2]) } ?: return null
    val year = m.groupValues[3].toIntOrNull()
    return runCatching {
        val d = LocalDate.of(year ?: today.year, month, m.groupValues[1].toInt())
        if (year == null && d < today) d.plusYears(1) else d
    }.getOrNull()
}

/**
 * The task editor (locked desktop-screens-v1 H): title, when, time, repeat, list, steps and a note, with the phone's
 * rules (TasksViewModel / TaskActions). A copy of a repeating task asks "this one" or "this and future".
 */
@Composable
fun TaskEditor(
    detail: TaskDetail?, seriesRule: RepeatRule?, steps: List<Subtask>, lists: List<ListInfo>, today: LocalDate,
    ui: TaskEditorUi, onClose: () -> Unit,
) {
    val t = LocalType.current
    var title by remember { mutableStateOf(detail?.title.orEmpty()) }
    var due by remember { mutableStateOf(detail?.due) }
    var dayText by remember { mutableStateOf(detail?.due?.toString().orEmpty()) }
    var time by remember { mutableStateOf(detail?.time?.toString().orEmpty()) }
    var rule by remember { mutableStateOf(detail?.repeat ?: seriesRule) }
    var nText by remember { mutableStateOf(((rule as? RepeatRule.EveryNDays)?.n ?: 2).toString()) }
    var listId by remember { mutableStateOf(detail?.listId) }
    var notes by remember { mutableStateOf(detail?.notes.orEmpty()) }
    var step by remember { mutableStateOf("") }
    var newList by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = detail?.let { saveScope(it.isTemplate, it.isInstance) } ?: SaveScope.ONE
    val titleFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
    // A copy's series rule can arrive after the copy (the phone does the same): adopt it, so "this and future" never
    // saves a series without its repeat (that would end it).
    androidx.compose.runtime.LaunchedEffect(seriesRule) { if (detail?.repeat == null && seriesRule != null && rule == null) { rule = seriesRule; nText = ((seriesRule as? RepeatRule.EveryNDays)?.n ?: 2).toString() } }
    fun draft(): TaskDraft {
        require(title.isNotBlank()) { "why don't you give the task a title." }
        // The phone's date picker can't pick a past day; an unchanged day (an old late task) is kept as it is.
        require(due == null || due!! >= today || due == detail?.due) { "pick today or a later day." }
        if (dayText.isNotBlank() && parseDay(dayText, today) == null && dayText.trim() != due?.toString()) throw IllegalArgumentException("write the day as 21 nov or 2026-11-21.")
        val tm = time.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(if (it.length == 4) "0$it" else it) }.getOrElse { throw IllegalArgumentException("write the time as hh:mm.") } }
        val r = when (val x = rule) {
            is RepeatRule.EveryNDays -> x.copy(n = nText.trim().toIntOrNull()?.takeIf { it >= 2 } ?: throw IllegalArgumentException("every n days needs n of 2 or more."), anchor = due ?: today)
            else -> x
        }
        return TaskDraft(title.trim(), notes.trim().takeIf { it.isNotEmpty() }, due, tm, listId?.let(UUID::fromString), r)
    }
    // One write at a time per action: Enter again (or held) while it ran made a second task/list/step (ultrareview).
    var saving by remember { mutableStateOf(false) }
    var listBusy by remember { mutableStateOf(false) }
    var stepBusy by remember { mutableStateOf(false) }
    fun save(s: SaveScope) {
        if (saving) return
        val d = runCatching { draft() }.getOrElse { error = it.message; return }
        saving = true
        val failed = { e: String -> saving = false; error = e }
        if (detail == null) ui.saveNew(d, onClose, failed)
        else ui.save(detail.id, detail.templateId ?: detail.id.takeIf { detail.isTemplate }, d, s, onClose, failed)
    }
    Sheet(if (detail == null) "~/tasks/new" else "~/tasks/${detail.title}", onClose) {
        BasicText("title", style = t.label, modifier = Modifier.padding(bottom = 4.dp))
        TermField(title, { title = it; error = null }, Modifier.focusRequester(titleFocus), onEnter = { if (scope == SaveScope.ASK) asking = true else save(scope) })
        BasicText("when", style = t.label, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Row {
            Choice("inbox", due == null) { due = null; dayText = "" }
            Choice("today", due == today) { due = today; dayText = today.toString() }
            Choice("tomorrow", due == today.plusDays(1)) { due = today.plusDays(1); dayText = due.toString() }
        }
        Row(Modifier.padding(top = 6.dp)) {
            BasicText("day (21 nov, 2026-11-21)", style = t.label, modifier = Modifier.weight(1.3f))
            Spacer(Modifier.width(10.dp))
            BasicText("time (hh:mm, optional)", style = t.label, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.3f)) { TermField(dayText, { dayText = it; if (it.isBlank()) due = null else parseDay(it, today)?.let { d -> due = d }; error = null }) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { TermField(time, { time = it; error = null }) }
        }
        BasicText(due?.let { "due ${it.short()}" + if (time.isNotBlank()) " at $time" else "" } ?: "no day: it waits in the inbox", style = t.label, modifier = Modifier.padding(top = 4.dp))
        BasicText("repeat", style = t.label, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Row {
            Choice("never", rule == null) { rule = null }
            Choice("daily", rule == RepeatRule.Daily) { rule = RepeatRule.Daily }
            Choice("weekly", rule is RepeatRule.Weekly) { rule = RepeatRule.Weekly(setOf((due ?: today).dayOfWeek)) }
        }
        Row(Modifier.padding(top = 4.dp)) {
            Choice("monthly", rule is RepeatRule.MonthlyOnDay) { rule = RepeatRule.MonthlyOnDay((due ?: today).dayOfMonth) }
            Choice("every n days", rule is RepeatRule.EveryNDays) { rule = RepeatRule.EveryNDays(nText.toIntOrNull() ?: 2, due ?: today) }
        }
        (rule as? RepeatRule.Weekly)?.let { w ->
            Row(Modifier.padding(top = 6.dp)) { DayOfWeek.entries.forEach { d -> Choice(d.name.take(2).lowercase(), d in w.days) { val n = if (d in w.days) w.days - d else w.days + d; if (n.isNotEmpty()) rule = RepeatRule.Weekly(n) } } }
        }
        (rule as? RepeatRule.MonthlyOnDay)?.let { BasicText("on day ${it.day} of each month", style = t.label, modifier = Modifier.padding(top = 4.dp)) }
        if (rule is RepeatRule.EveryNDays) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("every ", style = t.small); Column(Modifier.width(80.dp)) { TermField(nText, { nText = it; error = null }) }; BasicText(" days", style = t.small)
        }
        BasicText("list", style = t.label, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Row {
            Choice("inbox", listId == null) { listId = null }
            lists.filter { it.id != null }.forEach { l -> Choice("# ${l.name}", listId == l.id) { listId = l.id } }
            if (newList == null) Choice("+ list", false) { newList = "" }
        }
        newList?.let { nl ->
            Row(Modifier.padding(top = 6.dp)) { TermField(nl, { newList = it }, onEnter = {
                if (nl.isNotBlank() && !listBusy) { listBusy = true; ui.newList(nl.trim(), { listBusy = false; newList = null }) { listBusy = false; error = it } }
            }) }
            BasicText("enter makes the list; then pick it", style = t.label)
        }
        if (detail != null) {
            BasicText("steps", style = t.label, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            steps.forEach { s -> ItemRow(if (s.done) "[x]" else "[ ]", androidx.compose.ui.text.AnnotatedString(s.title), s.done, onBox = { ui.toggleStep(s.id) }) {
                BasicText("[x]", style = t.label, modifier = Modifier.clickable { ui.deleteStep(s.id) }) } }
            TermField(step, { step = it }, onEnter = {
                if (step.isNotBlank() && !stepBusy) { stepBusy = true; ui.addStep(detail.id, step.trim(), { stepBusy = false; step = "" }) { stepBusy = false; error = it } }
            })
            BasicText("enter adds a step", style = t.label)
        }
        BasicText("note", style = t.label, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        TermField(notes, { notes = it })
        error?.let { BasicText(it, style = t.small.copy(color = Fern.ember), modifier = Modifier.padding(top = 10.dp)) }
        if (asking) Column(Modifier.padding(top = 12.dp)) {
            BasicText("change only this copy, or this one and the ones after it?", style = t.small.copy(color = Fern.ink))
            Row(Modifier.padding(top = 6.dp)) { Choice("this one", false) { asking = false; save(SaveScope.ONE) }; Choice("this and future", true) { asking = false; save(SaveScope.SERIES) } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("[save ↵]", style = t.body.copy(color = Fern.mint), modifier = Modifier.clickable { if (scope == SaveScope.ASK) asking = true else save(scope) })
            Spacer(Modifier.weight(1f))
            val series = detail?.templateId ?: detail?.id?.takeIf { detail.isTemplate }
            if (series != null) BasicText("[stop repeating]", style = t.small, modifier = Modifier.padding(end = 14.dp).clickable { ui.stopRepeating(series, onClose) })
            if (detail != null) BasicText(if (confirmDelete) "[sure? delete]" else "[delete]", style = t.small.copy(color = Fern.ember),
                modifier = Modifier.clickable { if (confirmDelete) ui.delete(detail.id, onClose) else confirmDelete = true })
        }
    }
}
