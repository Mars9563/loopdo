package app.loopdo.android.today

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


/** Locked screen 1 (docs/design/app/app-screens-v2.html). */
@Composable
fun TodayScreen(vm: TodayViewModel, onSignOut: () -> Unit) {
    val model by vm.state.collectAsStateWithLifecycle()
    val snack by vm.snack.collectAsStateWithLifecycle()
    TodayContent(model, snack, vm, onSignOut)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodayContent(model: TodayModel?, snack: Snack?, vm: TodayUi, onSignOut: () -> Unit) {
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
            // Until the M9 settings screen, [⚙] only offers sign out (locked auth-onboarding-v1).
            if (settingsOpen) BracketRow(
                BracketItem("sign out", primary = true) { settingsOpen = false; onSignOut() },
                BracketItem("cancel", idle = true) { settingsOpen = false },
            )
            model?.let { TodaySections(it, vm) }
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
                onAddTask = { title, due, time, listId, listName -> vm.addTask(title, due, time, listId, listName, m.date, saved) },
                onAddJournal = { text -> vm.appendJournal(text, saved) },
            )
        }
    }
}

@Composable
private fun TodaySections(m: TodayModel, vm: TodayUi) {
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
            CheckRow(false, t.title, onClick = { vm.toggleTask(t) }) {
                Text("since " + t.lateSince!!.short().substringAfter(' '), style = LoopDoType.small.copy(color = LoopDoColors.warn))
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
            // A count habit's [ ] means "all done" (M5: no 35 taps); long-press sets the exact count.
            false, h.title, onClick = { if (isCount) vm.complete(h) else vm.tick(h) },
            onLongClick = if (isCount) ({ countSheet = h }) else null,
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
                ) {
                    if (h.kind == HabitKind.COUNT) Text("${h.count}/${h.target}", style = LoopDoType.small.copy(color = LoopDoColors.done))
                }
            }
            Toggle("[^]") { open = false }
        }
    }

    Label("TODAY", right = "${m.dueToday.count { !it.done }} left")
    if (m.dueToday.isEmpty()) Hint("nothing due today.")
    m.dueToday.forEach { t -> TaskLine(t, vm) }

    var inboxOpen by rememberSaveable { mutableStateOf(true) }
    Label("INBOX ${m.inbox.size}", right = if (inboxOpen) "[v]" else "[>]", rightColor = LoopDoColors.accent, onClick = { inboxOpen = !inboxOpen })
    if (inboxOpen) m.inbox.forEach { t -> TaskLine(t, vm) }

    if (m.lists.isNotEmpty()) {
        DashedRule(Modifier.padding(vertical = 8.dp))
        m.lists.forEach { g ->
            var open by rememberSaveable(g.id) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("# ${g.name}", style = LoopDoType.body.copy(color = LoopDoColors.dim), modifier = Modifier.weight(1f))
                Text("${g.items.size} " + if (open) "[v]" else "[>]", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            }
            if (open) g.items.forEach { t -> TaskLine(t, vm) }
        }
    }
}

@Composable
private fun TaskLine(t: TaskItem, vm: TodayUi) {
    CheckRow(t.done, t.title, onClick = { vm.toggleTask(t) }) {
        if (!t.done) {
            val tag = listOfNotNull(t.dueTime?.let { "@$it" }, t.listName?.let { "# $it" }).joinToString(" ")
            if (tag.isNotEmpty()) Text(tag, style = LoopDoType.small.copy(color = LoopDoColors.dim), maxLines = 1)
        }
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

/** One `[ ] title ... trailing` row; the whole row is the tap target (≥ 48 dp). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheckRow(done: Boolean, title: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val color = if (done) LoopDoColors.done else LoopDoColors.text
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .combinedClickable(role = Role.Checkbox, onClickLabel = if (done) "untick" else "tick", onClick = onClick, onLongClick = onLongClick),
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

