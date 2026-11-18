package app.loopdo.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.loopdo.client.journal.DayView
import app.loopdo.client.journal.LogItem
import app.loopdo.client.today.HabitItem
import app.loopdo.client.today.TaskItem
import app.loopdo.client.today.TodayModel
import app.loopdo.client.today.TodayUi
import app.loopdo.client.today.countdown
import app.loopdo.client.today.short
import app.loopdo.shared.habit.HabitKind
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val hm = DateTimeFormatter.ofPattern("HH:mm")

/** Small label row: "habits ……… 3/8" (lowercase, lichen; locked desktop-v1 `.lab`). */
@Composable
fun Label(left: String, right: String = "", leftColor: androidx.compose.ui.graphics.Color = Fern.lichen, rightContent: (@Composable () -> Unit)? = null) {
    val t = LocalType.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(left, style = t.label.copy(color = leftColor))
        Spacer(Modifier.weight(1f))
        if (rightContent != null) rightContent() else BasicText(right, style = t.label.copy(color = if (leftColor == Fern.ember) Fern.ember else Fern.ink))
    }
}

/** One 34 dp row: box, title, a right-aligned meta. */
@Composable
fun ItemRow(box: String, title: AnnotatedString, done: Boolean = false, onBox: (() -> Unit)? = null, onTitle: (() -> Unit)? = null, meta: @Composable () -> Unit = {}) {
    val t = LocalType.current
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        if (box.isNotEmpty()) {
            BasicText(box, style = t.body.copy(color = if (done) Fern.moss else Fern.lichen), modifier = if (onBox != null) Modifier.clickable(onClick = onBox) else Modifier)
            Spacer(Modifier.width(10.dp))
        }
        BasicText(title, style = t.body.copy(color = if (done) Fern.moss else Fern.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).then(if (onTitle != null) Modifier.clickable(onClick = onTitle) else Modifier))
        Spacer(Modifier.width(8.dp))
        meta()
    }
}

private fun focusTitle(title: String, focus: Boolean) = buildAnnotatedString {
    if (focus) { withStyle(SpanStyle(color = Fern.mint)) { append("› ") } }
    append(title)
}

/** The locked desktop-v1 A: header, streak + focus line, habits | tasks | today's journal. */
@Composable
fun TodayPane(
    m: TodayModel, ui: TodayUi, journal: DayView?, focusLine: String, onWrite: () -> Unit, onAdd: (String) -> Unit,
    writer: (@Composable () -> Unit)? = null, onOpenTask: (String) -> Unit = {}, onDeleteList: (String) -> Unit = {},
) {
    val t = LocalType.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("~/today", style = t.title.copy(color = Fern.mint))
            Spacer(Modifier.width(14.dp))
            BasicText(m.date.format(dayFmt).lowercase(Locale.ENGLISH), style = t.title.copy(color = Fern.hi))
            Spacer(Modifier.weight(1f))
            BasicText("[+ add]", style = t.small.copy(color = Fern.mint), modifier = Modifier.clickable { onAdd("") })
            Spacer(Modifier.width(22.dp))
            BasicText("[✎ write]", style = t.small.copy(color = Fern.ink), modifier = Modifier.clickable(onClick = onWrite))
        }
        Row(Modifier.fillMaxWidth().dashedBottom().padding(top = 6.dp, bottom = 14.dp)) {
            val s = m.streaks
            BasicText(buildAnnotatedString {
                append("jrnl "); withStyle(SpanStyle(color = Fern.mint)) { append("${s.journal}d") }
                append("    perfect "); withStyle(SpanStyle(color = Fern.mint)) { append("${s.perfect}d") }
                append("    active "); withStyle(SpanStyle(color = Fern.mint)) { append("${s.active}d") }
                if (s.freezes > 0) { append(" "); withStyle(SpanStyle(fontFamily = Glyphs, color = Fern.mint)) { append("\u2744\uFE0E") }; append("${s.freezes}") }
            }, style = t.small)
            Spacer(Modifier.weight(1f))
            if (focusLine.isNotEmpty()) BasicText(focusLine, style = t.small)
        }
        Row(Modifier.fillMaxSize().padding(top = 4.dp)) {
            Column(Modifier.weight(1.1f).fillMaxHeight().dashedRight().padding(top = 14.dp, end = 26.dp).verticalScroll(rememberScrollState())) {
                HabitsColumn(m, ui)
            }
            Column(Modifier.weight(1f).fillMaxHeight().dashedRight().padding(top = 14.dp, start = 26.dp, end = 26.dp).verticalScroll(rememberScrollState())) {
                TasksColumn(m, ui, onAdd, onOpenTask, onDeleteList)
            }
            Column(Modifier.weight(1.05f).fillMaxHeight().padding(top = 14.dp, start = 26.dp).verticalScroll(rememberScrollState())) {
                JournalColumn(journal, onWrite, writer)
            }
        }
    }
}

@Composable
private fun ColumnScope.HabitsColumn(m: TodayModel, ui: TodayUi) {
    val t = LocalType.current
    val total = m.habitsOpen.size + m.habitsDone.size
    Label("habits", "${m.habitsDone.size}/$total")
    if (total == 0) BasicText("no habits due today.", style = t.small)
    m.habitsOpen.forEach { h -> HabitLine(h, ui, done = false) }
    if (m.habitsDone.isNotEmpty()) Spacer(Modifier.height(14.dp))
    m.habitsDone.forEach { h -> HabitLine(h, ui, done = true) }
}

@Composable
private fun HabitLine(h: HabitItem, ui: TodayUi, done: Boolean) {
    val t = LocalType.current
    val count = h.kind == HabitKind.COUNT
    ItemRow(if (done) "[x]" else "[ ]", focusTitle(h.title, h.focus != null), done, onBox = { if (done) ui.untick(h) else if (count) ui.complete(h) else ui.tick(h) }) {
        when {
            done -> BasicText(if (h.streak > 0) "${h.streak}d" else "", style = t.small.copy(color = Fern.moss))
            h.focus != null -> BasicText(buildAnnotatedString {
                withStyle(SpanStyle(color = Fern.ink)) { append("${h.focus!!.done}/${h.focus!!.goal}") }; append(" this week")
                h.reminderTime?.let { append(" · @${it.format(hm)}") }
            }, style = t.small)
            count -> Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("${h.count}/${h.target}", style = t.small.copy(color = Fern.ink))
                Spacer(Modifier.width(8.dp))
                BasicText("[+${h.step}]", style = t.small.copy(color = Fern.mint), modifier = Modifier.clickable { ui.tick(h) })
            }
            h.reminderTime != null -> BasicText("@${h.reminderTime!!.format(hm)}", style = t.small)
        }
    }
}

@Composable
private fun ColumnScope.TasksColumn(m: TodayModel, ui: TodayUi, onAdd: (String) -> Unit, onOpen: (String) -> Unit, onDeleteList: (String) -> Unit) {
    val t = LocalType.current
    if (m.late.isNotEmpty()) {
        Label("late", "${m.late.size}", leftColor = Fern.ember)
        m.late.forEach { task -> TaskLine(task, ui, onOpen) { BasicText(task.lateSince?.let { "since ${it.short()}" } ?: "late", style = t.small.copy(color = Fern.ember)) } }
        Spacer(Modifier.height(14.dp))
    }
    val left = m.dueToday.count { !it.done }
    Label("today", "$left left")
    if (m.dueToday.isEmpty()) BasicText("nothing due today.", style = t.small)
    m.dueToday.forEach { task -> TaskLine(task, ui, onOpen) { BasicText(if (task.focus) "focus" else task.listName?.let { "# $it" } ?: task.dueTime?.format(hm).orEmpty(), style = t.small) } }
    Spacer(Modifier.height(14.dp))
    Label("inbox", "${m.inbox.size}")
    if (m.inbox.isEmpty()) BasicText("inbox is empty. [+ add] puts tasks here.", style = t.small)
    m.inbox.forEach { task -> TaskLine(task, ui, onOpen) { BasicText(task.due?.short().orEmpty(), style = t.small) } }
    if (m.lists.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Label("lists")
        m.lists.forEach { l -> ListGroupRows(l, ui, onOpen, onDeleteList) }
    }
}

@Composable
private fun TaskLine(task: TaskItem, ui: TodayUi, onOpen: (String) -> Unit, meta: @Composable () -> Unit) =
    ItemRow(if (task.done) "[x]" else "[ ]", focusTitle(task.title, task.focus), task.done, onBox = { ui.toggleTask(task) }, onTitle = { onOpen(task.id) }) { if (!task.done) meta() }

/** `# work 3 [>]`: click to open the list's tasks under it; right-click to delete the list (its tasks go to the inbox). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ListGroupRows(l: app.loopdo.client.today.ListGroup, ui: TodayUi, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    val t = LocalType.current
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.ContextMenuArea(items = { listOf(androidx.compose.foundation.ContextMenuItem("delete list") { onDelete(l.id) }) }) {
        ItemRow("", AnnotatedString("# ${l.name}"), onTitle = { open = !open }) {
            BasicText("${l.items.size} ${if (open) "[^]" else "[>]"}", style = t.small, modifier = Modifier.clickable { open = !open })
        }
    }
    if (open) Column(Modifier.padding(start = 16.dp)) { l.items.forEach { task -> TaskLine(task, ui, onOpen) { BasicText(task.due?.short().orEmpty(), style = t.small) } } }
}

@Composable
private fun ColumnScope.JournalColumn(day: DayView?, onWrite: () -> Unit, writer: (@Composable () -> Unit)?) {
    val t = LocalType.current
    Label("journal", day?.moodFace.orEmpty())
    val lines = day?.blocks?.flatMap { it.items }?.filterIsInstance<LogItem.Line>().orEmpty()
    if (lines.isEmpty()) BasicText("nothing written today yet.", style = t.small)
    lines.forEach { l ->
        Row(Modifier.padding(vertical = 5.dp)) {
            BasicText(l.line.time?.let { "[${it.format(hm)}]" } ?: "[--:--]", style = t.body.copy(color = Fern.mint))
            Spacer(Modifier.width(12.dp))
            BasicText(l.line.text, style = t.body)
        }
    }
    Spacer(Modifier.height(14.dp))
    if (writer != null) writer() else Column(
        Modifier.fillMaxWidth().border(1.dp, Fern.mint.copy(alpha = .30f), RoundedCornerShape(12.dp))
            .clickable(onClick = onWrite).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicText("what's happening?", style = t.body.copy(color = Fern.lichen))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("[📷 photo]", style = t.small)
            BasicText("[save ↵]", style = t.small.copy(color = Fern.mint))
        }
    }
}

/** "focus › gym 4× · › pay rent" from this week's focus marks (empty when there is none). */
fun focusLine(habits: List<HabitItem>, tasks: List<TaskItem>): String {
    val items = habits.filter { it.focus != null }.map { "› ${it.title} ${it.focus!!.goal}×" } + tasks.filter { it.focus }.map { "› ${it.title}" }
    return if (items.isEmpty()) "" else "focus " + items.distinct().joinToString(" · ")
}
