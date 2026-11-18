package app.loopdo.android.today
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.shared.habit.HabitKind
import kotlinx.coroutines.delay


/**
 * What the today screen offers beyond ticking (M5): a task's name opens its editor, a long-press
 * opens the quick menu, list headers open the lists screen, and the REPEATING section.
 */
class TodayExtras(
    val repeating: List<app.loopdo.android.tasks.RepeatingRow>,
    val onOpenTask: (String) -> Unit,
    val onTaskMenu: (TaskItem) -> Unit,
    val onOpenLists: () -> Unit,
    /** A habit's name → its detail on the habits tab (spec: reached from a habit name on today). */
    val onOpenHabit: (String) -> Unit = {},
    /** The app lock switch under [⚙] (M6); null = not offered. */
    val lockOn: Boolean? = null,
    val onToggleLock: () -> Unit = {},
    /** M7: notifications refused → [⚙] offers Android's notification settings. */
    val notificationsOff: Boolean = false,
    val onAllowNotifications: () -> Unit = {},
)

/** Locked screen 1 (docs/design/app/app-screens-v2.html), plus the task editor, quick menu and lists (M5). [openTask] = a deep link. */
@Composable
fun TodayScreen(vm: TodayViewModel, tasks: app.loopdo.android.tasks.TasksViewModel, openTask: OpenTask?, onLinkHandled: () -> Unit = {}, onOpenHabit: (String) -> Unit = {}, lockOn: Boolean? = null, onToggleLock: () -> Unit = {}, notificationsOff: Boolean = false, onAllowNotifications: () -> Unit = {}, onSignOut: () -> Unit) {
    val model by vm.state.collectAsStateWithLifecycle()
    val snack by vm.snack.collectAsStateWithLifecycle()
    val templates by tasks.templates.collectAsStateWithLifecycle()
    val lists by tasks.lists.collectAsStateWithLifecycle()
    val taskSnack by tasks.message.collectAsStateWithLifecycle()
    /** "today", "task:<id>" or "lists"; saved across rotation. */
    var route by rememberSaveable { mutableStateOf("today") }
    var menu by remember { mutableStateOf<TaskItem?>(null) }
    LaunchedEffect(openTask) { openTask?.let { route = "task:${it.id}"; onLinkHandled() } }
    androidx.activity.compose.BackHandler(enabled = route != "today") { route = "today" }
    val today = model?.date
    Box(Modifier.fillMaxSize()) {
        when {
            route.startsWith("task:") && today != null -> app.loopdo.android.tasks.TaskEditorScreen(
                route.removePrefix("task:"), today, tasks, onClose = { route = "today" }, onOpenLists = { route = "lists" },
            )
            route == "lists" -> app.loopdo.android.tasks.ListsScreen(tasks) { route = "today" }
            else -> TodayContent(
                model, snack, vm, onSignOut,
                TodayExtras(
                    repeating = today?.let { app.loopdo.android.tasks.repeatingRows(templates, it) }.orEmpty(),
                    onOpenTask = { route = "task:$it" }, onTaskMenu = { menu = it }, onOpenLists = { route = "lists" }, onOpenHabit = onOpenHabit, lockOn = lockOn, onToggleLock = onToggleLock,
                    notificationsOff = notificationsOff, onAllowNotifications = onAllowNotifications,
                ),
            )
        }
        taskSnack?.let { s ->
            LaunchedEffect(s.id) { delay(3_500); tasks.dismiss(s.id) }
            Text(
                s.text, style = LoopDoType.small,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth()
                    .shadow(10.dp, RoundedCornerShape(12.dp)).background(Color(0xF51E283A), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
    }
    menu?.let { t ->
        if (today != null) app.loopdo.android.tasks.TaskMenuSheet(t, today, lists, tasks, onEdit = { route = "task:${t.id}" }, onClose = { menu = null })
    }
}

/** A deep link to one task (the widget's task name); [nonce] makes the same link work twice. */
data class OpenTask(val id: String, val nonce: Long = System.nanoTime())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodayContent(model: TodayModel?, snack: Snack?, vm: TodayUi, onSignOut: () -> Unit, extras: TodayExtras? = null) {
    var adding by rememberSaveable { mutableStateOf(false) }
    // The add sheet's words live here, not in the sheet: closing it (Back, a stray tap) or a failed
    // save never throws typed text away. It is cleared only once the text is stored.
    var draftText by rememberSaveable { mutableStateOf("") }
    var draftJournal by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = LoopDoColors.accent)) { append("~/today") }
                        model?.let { append(" " + it.date.short()) }
                    },
                    style = LoopDoType.title,
                    modifier = Modifier.weight(1f).then(
                        // Debug builds only: long-press the title to send a forged tick (exit check 5).
                        if (app.loopdo.android.BuildConfig.DEBUG) Modifier.combinedClickable(onClick = {}, onLongClick = { vm.debugForgedTick() })
                        else Modifier,
                    ),
                )
                model?.let { Text("⏳ " + it.endsIn.countdown(), style = LoopDoType.small.copy(color = LoopDoColors.dim)) }
                Bracket("⚙", idle = !settingsOpen) { settingsOpen = !settingsOpen }
            }
            // Until the M9 settings screen, [⚙] offers sign out, and lists (M5).
            if (settingsOpen) BracketRow(
                *listOfNotNull(
                    extras?.let { e -> BracketItem("lists") { settingsOpen = false; e.onOpenLists() } },
                    extras?.lockOn?.let { on -> BracketItem(if (on) "lock: on" else "lock: off", primary = on, idle = !on) { settingsOpen = false; extras.onToggleLock() } },
                    extras?.takeIf { it.notificationsOff }?.let { e -> BracketItem("notifications off · allow") { settingsOpen = false; e.onAllowNotifications() } },
                    BracketItem("sign out", primary = true) { settingsOpen = false; onSignOut() },
                    BracketItem("cancel", idle = true) { settingsOpen = false },
                ).toTypedArray(),
            )
            model?.let { TodaySections(it, vm, extras) }
            Spacer(Modifier.height(if (snack != null) 140.dp else 88.dp)) // room for [+ add] and the snackbar
        }

        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (snack != null) 70.dp else 14.dp)
                .shadow(10.dp, RoundedCornerShape(16.dp))
                .background(Color(0xEB182438), RoundedCornerShape(16.dp))
                .border(1.dp, LoopDoColors.accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button) { adding = true }
                .height(48.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Centre the glyphs, not the line box: JetBrains Mono has more room below than above.
            Text(
                "[+ add]",
                style = LoopDoType.control.copy(
                    color = LoopDoColors.accent, fontWeight = FontWeight.SemiBold,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
            )
        }

        snack?.let { s ->
            LaunchedEffect(s.id) { delay(if (s.undo != null) 5_000 else 3_000); vm.dismissSnack(s.id) }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth()
                    .shadow(10.dp, RoundedCornerShape(12.dp))
                    .background(Color(0xF51E283A), RoundedCornerShape(12.dp))
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.text, style = LoopDoType.small, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                if (s.undo != null) Bracket("undo", primary = true) { vm.undo(s) }
            }
        }

        if (adding) model?.let { m ->
            val saved = { adding = false; draftText = "" }
            AddSheet(
                today = m.date,
                lists = m.lists.map { it.id to it.name },
                text = draftText, onText = { draftText = it },
                journal = draftJournal, onJournal = { draftJournal = it },
                onDismiss = { adding = false },
                onAddTask = { title, due, time, listId, listName, repeat -> vm.addTask(title, due, time, listId, listName, m.date, repeat, saved) },
                onAddJournal = { text -> vm.appendJournal(text, saved) },
            )
        }
    }
}

@Composable
private fun TodaySections(m: TodayModel, vm: TodayUi, extras: TodayExtras?) {
    val s = m.streaks
    Text(
        buildAnnotatedString {
            fun part(name: String, value: String) {
                withStyle(SpanStyle(color = LoopDoColors.accent)) { append(name) }
                append(" $value")
            }
            part("jrnl", "${s.journal}d · ")
            part("perfect", "${s.perfect}d · ")
            part("active", "${s.active}d")
            if (s.freezes > 0) append(" ❄${s.freezes}")
        },
        style = LoopDoType.small, modifier = Modifier.padding(bottom = 8.dp),
    )
    DashedRule()

    if (m.late.isNotEmpty()) {
        Label("LATE ${m.late.size}", color = LoopDoColors.warn)
        m.late.forEach { t ->
            TaskRow(t, vm, extras) {
                // Owner, 2026-11-18: a series missed for days is one late copy with how many others were missed.
                val missed = if (t.missed > 0) "missed ${t.missed} · " else ""
                Text(missed + "since " + t.lateSince!!.short().substringAfter(' '), style = LoopDoType.small.copy(color = LoopDoColors.warn))
            }
        }
    }

    val total = m.habitsOpen.size + m.habitsDone.size
    Label("HABITS", right = "${m.habitsDone.size}/$total")
    if (total == 0) Hint("no habits due today.")
    var countSheet by remember { mutableStateOf<HabitItem?>(null) }
    countSheet?.let { h -> CountSheet(h, vm) { countSheet = null } }
    m.habitsOpen.forEach { h ->
        val isCount = h.kind == HabitKind.COUNT
        CheckRow(
            // [ ] ticks; on a count habit it means "all done" (M5: no 35 taps). The name opens the habit's
            // detail (owner, 2026-11-17); long-press a count habit to set the exact count.
            false, h.title, onClick = { if (isCount) vm.complete(h) else vm.tick(h) },
            onLongClick = if (isCount) ({ countSheet = h }) else null,
            onName = extras?.let { e -> { e.onOpenHabit(h.id) } },
        ) {
            when {
                isCount -> {
                    Text("${h.count}/${h.target}" + h.unit.orEmpty().let { if (it.isEmpty()) "" else " $it" }, style = LoopDoType.small)
                    Bracket("+${h.step}", primary = true) { vm.tick(h) }
                }
                h.reminderTime != null -> Text("@" + h.reminderTime, style = LoopDoType.small.copy(color = LoopDoColors.dim))
            }
        }
    }
    if (m.habitsDone.isNotEmpty()) {
        var open by rememberSaveable { mutableStateOf(false) }
        if (!open) {
            CheckRow(true, m.habitsDone.joinToString(" · ") { it.title }, onClick = { open = true })
        } else {
            m.habitsDone.forEach { h ->
                CheckRow(
                    true, h.title,
                    onClick = { if (h.kind == HabitKind.COUNT) vm.tick(h) else vm.untick(h) },
                    onLongClick = if (h.kind == HabitKind.COUNT) ({ countSheet = h }) else null,
                    onName = extras?.let { e -> { e.onOpenHabit(h.id) } },
                ) {
                    if (h.kind == HabitKind.COUNT) Text("${h.count}/${h.target}", style = LoopDoType.small.copy(color = LoopDoColors.done))
                }
            }
            Toggle("[^]") { open = false }
        }
    }

    Label("TODAY", right = "${m.dueToday.count { !it.done }} left")
    if (m.dueToday.isEmpty()) Hint("nothing due today.")
    m.dueToday.forEach { t -> TaskLine(t, vm, extras) }

    var inboxOpen by rememberSaveable { mutableStateOf(true) }
    Label("INBOX ${m.inbox.size}", right = if (inboxOpen) "[v]" else "[>]", rightColor = LoopDoColors.accent, onClick = { inboxOpen = !inboxOpen })
    if (inboxOpen) m.inbox.forEach { t -> TaskLine(t, vm, extras) }

    if (m.lists.isNotEmpty()) {
        DashedRule(Modifier.padding(vertical = 8.dp))
        m.lists.forEach { g ->
            var open by rememberSaveable(g.id) { mutableStateOf(false) }
            Row(
                // Long-press a list header → the lists screen (rename, delete).
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .combinedClickable(role = Role.Button, onClick = { open = !open }, onLongClick = extras?.onOpenLists),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("# ${g.name}", style = LoopDoType.body.copy(color = LoopDoColors.dim), modifier = Modifier.weight(1f))
                Text("${g.items.size} " + if (open) "[v]" else "[>]", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            }
            if (open) g.items.forEach { t -> TaskLine(t, vm, extras) }
        }
    }

    // Repeating tasks live here, collapsed: each with its next date; tap → the series in the editor.
    if (extras != null && extras.repeating.isNotEmpty()) {
        var open by rememberSaveable { mutableStateOf(false) }
        DashedRule(Modifier.padding(vertical = 8.dp))
        Label("↻ REPEATING ${extras.repeating.size}", right = if (open) "[v]" else "[>]", rightColor = LoopDoColors.accent, onClick = { open = !open })
        if (open) extras.repeating.forEach { r ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { extras.onOpenTask(r.id) }, verticalAlignment = Alignment.CenterVertically) {
                Text("↻ " + r.line, style = LoopDoType.body.copy(color = LoopDoColors.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TaskLine(t: TaskItem, vm: TodayUi, extras: TodayExtras?) {
    TaskRow(t, vm, extras) {
        if (!t.done) {
            val tag = listOfNotNull(t.dueTime?.let { "@$it" }, t.listName?.let { "# $it" }).joinToString(" ")
            if (tag.isNotEmpty()) Text(tag, style = LoopDoType.small.copy(color = LoopDoColors.dim), maxLines = 1)
        }
    }
}

/**
 * A task: `[ ]` ticks it; the name opens the editor; a long-press anywhere opens the quick menu
 * (README screen 1: "tap a name to edit; long-press to reschedule or move to a list"). Without
 * [extras] (the debug demo) the whole row ticks, as before.
 */
@Composable
private fun TaskRow(t: TaskItem, vm: TodayUi, extras: TodayExtras?, trailing: @Composable RowScope.() -> Unit) {
    if (extras == null) return CheckRow(t.done, t.title, onClick = { vm.toggleTask(t) }, trailing = trailing)
    val color = if (t.done) LoopDoColors.done else LoopDoColors.text
    val tick by androidx.compose.runtime.rememberUpdatedState({ vm.toggleTask(t) })
    val open by androidx.compose.runtime.rememberUpdatedState({ extras.onOpenTask(t.id) })
    val menu by androidx.compose.runtime.rememberUpdatedState({ extras.onTaskMenu(t) })
    Row(
        // Looks exactly like the other rows; the first 48 dp (the [ ] and its gap) ticks, the rest opens the editor.
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (it.x < 48.dp.toPx()) tick() else open() },
                    onLongPress = { menu() },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Checkbox
                onClick(label = "edit") { open(); true }
                onLongClick(label = "move or delete") { menu(); true }
                customActions = listOf(CustomAccessibilityAction(if (t.done) "untick" else "tick") { tick(); true })
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (t.done) "[x]" else "[ ]", style = LoopDoType.body.copy(color = color))
        Text(t.title, style = LoopDoType.body.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** `HABITS        3/5`: small uppercase label, optional right side. */
@Composable
private fun Label(text: String, color: Color = LoopDoColors.dim, right: String? = null, rightColor: Color = LoopDoColors.dim, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick) else Modifier.padding(top = 14.dp, bottom = 2.dp)),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = LoopDoType.label.copy(color = color))
        right?.let { Text(it, style = LoopDoType.label.copy(color = rightColor)) }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(vertical = 6.dp))

@Composable
private fun Toggle(label: String, onClick: () -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Text(label, style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(12.dp))
}

/**
 * One `[ ] title ... trailing` row (≥ 48 dp). Without [onName] the whole row is the tap target; with it,
 * the first 48 dp (the [ ] and its gap) ticks and the rest opens the name, the row looking the same.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheckRow(
    done: Boolean, title: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    onName: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {},
) {
    val color = if (done) LoopDoColors.done else LoopDoColors.text
    val tick by androidx.compose.runtime.rememberUpdatedState(onClick)
    val open by androidx.compose.runtime.rememberUpdatedState(onName)
    val hold by androidx.compose.runtime.rememberUpdatedState(onLongClick)
    val touch = if (onName == null) {
        Modifier.combinedClickable(role = Role.Checkbox, onClickLabel = if (done) "untick" else "tick", onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (it.x < 48.dp.toPx()) tick() else open?.invoke() },
                    onLongPress = { hold?.invoke() },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Checkbox
                onClick(label = "open") { open?.invoke(); true }
                hold?.let { h -> onLongClick(label = "set the count") { h(); true } }
                customActions = listOf(CustomAccessibilityAction(if (done) "untick" else "tick") { tick(); true })
            }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (done) "[x]" else "[ ]", style = LoopDoType.body.copy(color = color))
        Text(title, style = LoopDoType.body.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

/**
 * Long-press a count habit (locked habits-tasks-v1 screen 1): step back or forward, type the
 * exact number, or "all done". Each choice is one action with undo.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CountSheet(h: HabitItem, vm: TodayUi, onClose: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val none = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.ui.window.Popup(onDismissRequest = onClose, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(none, indication = null, onClick = onClose),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier.padding(14.dp).fillMaxWidth().background(Color(0xF0101826), shape)
                    .border(1.dp, Color(0x33BED7FF), shape).clickable(none, indication = null) {}.padding(16.dp),
            ) {
                Text(
                    "${h.title}: ${h.count}/${h.target}" + h.unit.orEmpty().let { if (it.isEmpty()) "" else " $it" },
                    style = LoopDoType.body,
                )
                fun act(block: () -> Unit) { block(); onClose() }
                androidx.compose.foundation.layout.FlowRow {
                    if (h.step > 1) Bracket("−${h.step}", idle = true) { act { vm.setCount(h, (h.count - h.step).coerceAtLeast(0)) } }
                    Bracket("−1", idle = true) { act { vm.setCount(h, (h.count - 1).coerceAtLeast(0)) } }
                    Bracket("+1") { act { vm.setCount(h, h.count + 1) } }
                    if (h.step > 1) Bracket("+${h.step}") { act { vm.setCount(h, h.count + h.step) } }
                    Bracket("all ${h.target} ✓", primary = true) { act { vm.complete(h) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).border(1.dp, LoopDoColors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (typed.isEmpty()) Text("type the count", style = LoopDoType.body.copy(color = LoopDoColors.done))
                        androidx.compose.foundation.text.BasicTextField(
                            typed, { v -> typed = v.filter { it.isDigit() }.take(5) }, singleLine = true, textStyle = LoopDoType.body,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(LoopDoColors.accent),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Bracket("set", primary = true, enabled = typed.isNotEmpty()) { act { vm.setCount(h, typed.toInt()) } }
                }
            }
        }
    }
}

