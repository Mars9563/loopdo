package app.loopdo.android.habits

import app.loopdo.client.habits.short

import app.loopdo.client.habits.HabitLine
import app.loopdo.client.habits.HabitsList
import app.loopdo.client.habits.StripCell
import app.loopdo.client.habits.editorStart
import app.loopdo.client.habits.habitDetail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import java.time.YearMonth
import java.util.UUID
import kotlin.math.roundToInt

/** Where the habits tab is: the list, one habit, its editor, or a new habit. Saved as text across rotation. */
private sealed interface Route {
    data object List : Route
    data class Detail(val id: String) : Route
    data class Edit(val id: String) : Route
    data object New : Route
}

private fun Route.save(): String = when (this) {
    Route.List -> "list"
    is Route.Detail -> "detail:$id"
    is Route.Edit -> "edit:$id"
    Route.New -> "new"
}

private fun route(s: String): Route = when {
    s.startsWith("detail:") -> Route.Detail(s.removePrefix("detail:"))
    s.startsWith("edit:") -> Route.Edit(s.removePrefix("edit:"))
    s == "new" -> Route.New
    else -> Route.List
}

/** The habits tab. [openHabit] (a deep link) opens that habit's detail. */
@Composable
fun HabitsTab(vm: HabitsViewModel, openHabit: String? = null, linkNonce: Long = 0L, onLinkHandled: () -> Unit = {}) {
    val data by vm.data.collectAsStateWithLifecycle()
    val snack by vm.message.collectAsStateWithLifecycle()
    var saved by rememberSaveable { mutableStateOf(openHabit?.let { Route.Detail(it).save() } ?: "list") }
    LaunchedEffect(openHabit, linkNonce) { openHabit?.let { saved = Route.Detail(it).save(); onLinkHandled() } }
    val at = route(saved)
    val go = { r: Route -> saved = r.save() }
    BackHandler(enabled = at != Route.List) {
        go(if (at is Route.Edit) Route.Detail(at.id) else Route.List)
    }
    Box(Modifier.fillMaxSize()) {
        val d = data
        when {
            d == null -> Unit
            at is Route.Detail -> {
                var month by rememberSaveable(at.id) { mutableStateOf(YearMonth.from(d.today).toString()) }
                val row = d.habit(at.id)
                val detail = remember(d, at.id, month) { row?.let { habitDetail(it, d.schedules, d.logs, d.today, YearMonth.parse(month)) } }
                if (detail == null) LaunchedEffect(Unit) { go(Route.List) }
                else HabitDetailScreen(
                    detail, today = d.today, canEdit = row?.archivedOn == null,
                    onBack = { go(Route.List) }, onEdit = { go(Route.Edit(at.id)) },
                    onMonth = { month = it.toString() },
                )
            }
            at is Route.Edit -> {
                val row = d.habit(at.id)
                if (row == null) LaunchedEffect(Unit) { go(Route.List) }
                else HabitEditorScreen(
                    habitId = at.id, start = remember(at.id) { editorStart(row, d.schedules, d.logs, d.today) },
                    live = editorStart(row, d.schedules, d.logs, d.today), today = d.today, vm = vm,
                    onClose = { go(Route.Detail(at.id)) }, onArchived = { go(Route.List) },
                )
            }
            at is Route.New -> HabitEditorScreen(
                habitId = null, start = null, live = null, today = d.today, vm = vm,
                onClose = { go(Route.List) }, onArchived = { go(Route.List) },
            )
            else -> HabitsListScreen(d.list, vm, onOpen = { go(Route.Detail(it)) }, onNew = { go(Route.New) })
        }
        snack?.let { s ->
            LaunchedEffect(s.id) { delay(3_500); vm.dismiss(s.id) }
            SnackLine(s.text, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Locked habits-tasks-v1, screen 2. */
@Composable
private fun HabitsListScreen(list: HabitsList, vm: HabitsViewModel, onOpen: (String) -> Unit, onNew: () -> Unit) {
    var archivedOpen by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("~/habits", style = LoopDoType.title.copy(color = LoopDoColors.accent), modifier = Modifier.weight(1f))
                Text("${list.active.size} active", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            }
            if (list.active.isEmpty() && list.paused.isEmpty() && list.archived.isEmpty()) {
                Text("no habits yet. [+ new] makes one.", style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(top = 16.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("LAST 7 DAYS", style = LoopDoType.label, modifier = Modifier.weight(1f))
                    list.stripDays.forEach { day ->
                        Text(
                            day.dayOfWeek.short().take(1), textAlign = TextAlign.Center, modifier = Modifier.width(CELL),
                            style = LoopDoType.label.copy(
                                letterSpacing = LoopDoType.body.letterSpacing,
                                color = if (day == list.today) LoopDoColors.text else LoopDoColors.dim,
                                fontWeight = if (day == list.today) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        )
                    }
                    Spacer(Modifier.width(STREAK))
                }
                ReorderableHabits(list.active, onOpen) { ids -> vm.reorder(ids.map(UUID::fromString)) }
                DashedRule(Modifier.padding(vertical = 6.dp))
                SectionLine("paused", list.paused.size.toString())
                list.paused.forEach { HabitLineRow(it, onOpen) }
                SectionLine("archived", list.archived.size.toString(), if (list.archived.isEmpty()) null else if (archivedOpen) "v" else ">") { archivedOpen = !archivedOpen }
                if (archivedOpen) list.archived.forEach { h ->
                    Row(Modifier.fillMaxWidth().height(48.dp).clickable { onOpen(h.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(h.title, style = LoopDoType.body.copy(color = LoopDoColors.dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        h.note?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.done)) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Bracket("restore", primary = true) { vm.restore(UUID.fromString(h.id)) }
                        Bracket("delete forever", danger = true) { deleting = h.id }
                    }
                }
                DashedRule(Modifier.padding(vertical = 6.dp))
                Text(
                    "■ done · □ missed · ❄ saved by a freeze · ◦ not due · · today, not yet\ntap a habit → its detail. long-press → drag to reorder.",
                    style = LoopDoType.small.copy(color = LoopDoColors.dim),
                )
            }
            Spacer(Modifier.height(140.dp)) // room for [+ new] and a message
        }
        Fab("+ new", Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 14.dp), onNew)
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Color(0xF51E283A),
            title = { Text("delete forever?", style = LoopDoType.title) },
            text = { Text("it disappears everywhere; its past days still count in your streaks.", style = LoopDoType.body.copy(color = LoopDoColors.dim)) },
            confirmButton = { Bracket("delete forever", danger = true) { deleting = null; vm.deleteForever(UUID.fromString(id)) } },
            dismissButton = { Bracket("cancel", idle = true) { deleting = null } },
        )
    }
}

/** The active habits: tap = detail; long-press, then drag = a new order (also today's and the widget's). */
@Composable
private fun ReorderableHabits(habits: List<HabitLine>, onOpen: (String) -> Unit, onReorder: (List<String>) -> Unit) {
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val current by rememberUpdatedState(habits)
    val from = habits.indexOfFirst { it.id == dragging }
    val to = if (from < 0) -1 else (from + (offset / rowPx).roundToInt()).coerceIn(0, habits.lastIndex)
    habits.forEachIndexed { i, h ->
        // Rows between the dragged one's old and new place shift by one row to make room.
        val shift = when {
            from < 0 || i == from -> 0f
            i in (from + 1)..to -> -rowPx
            i in to until from -> rowPx
            else -> 0f
        }
        HabitLineRow(
            h, onOpen,
            Modifier
                .graphicsLayer { translationY = if (i == from) offset else shift }
                .then(if (i == from) Modifier.background(Color(0x331E283A)) else Modifier)
                .pointerInput(h.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); dragging = h.id; offset = 0f },
                        onDrag = { change, amount -> change.consume(); offset += amount.y },
                        onDragEnd = {
                            val list = current.map { it.id }.toMutableList()
                            val a = list.indexOf(dragging)
                            if (a >= 0) {
                                val b = (a + (offset / rowPx).roundToInt()).coerceIn(0, list.lastIndex)
                                if (a != b) { list.add(b, list.removeAt(a)); onReorder(list) }
                            }
                            dragging = null; offset = 0f
                        },
                        onDragCancel = { dragging = null; offset = 0f },
                    )
                },
        )
    }
}

@Composable
private fun HabitLineRow(h: HabitLine, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(ROW).clickable(role = Role.Button, onClickLabel = "open") { onOpen(h.id) }, verticalAlignment = Alignment.CenterVertically) {
        Text(h.title, style = LoopDoType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
        h.strip.forEach { StripGlyph(it) }
        Text(
            h.note ?: "${h.streak}d", textAlign = TextAlign.End, maxLines = 1,
            style = LoopDoType.small.copy(color = LoopDoColors.dim),
            modifier = if (h.note == null) Modifier.width(STREAK) else Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun StripGlyph(c: StripCell) {
    val (glyph, color) = when (c) {
        StripCell.DONE -> "■" to LoopDoColors.accent
        StripCell.MISSED -> "□" to LoopDoColors.warn
        StripCell.FROZEN -> "❄" to LoopDoColors.accent
        StripCell.NOT_DUE -> "◦" to LoopDoColors.done
        StripCell.TODAY -> "·" to LoopDoColors.text
    }
    Text(glyph, textAlign = TextAlign.Center, style = LoopDoType.small.copy(color = color), modifier = Modifier.width(CELL))
}

@Composable
private fun SectionLine(label: String, count: String, toggle: String? = null, onToggle: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).then(if (toggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LoopDoType.body.copy(color = LoopDoColors.dim), modifier = Modifier.weight(1f))
        Text(count + (toggle?.let { " [$it]" } ?: ""), style = LoopDoType.small.copy(color = LoopDoColors.dim))
    }
}

/** The one floating action of a screen (same look as today's [+ add]). */
@Composable
internal fun Fab(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .background(Color(0xEB182438), RoundedCornerShape(16.dp))
            .border(1.dp, LoopDoColors.accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .height(48.dp).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "[$label]",
            style = LoopDoType.control.copy(
                color = LoopDoColors.accent, fontWeight = FontWeight.SemiBold,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

@Composable
private fun SnackLine(text: String, modifier: Modifier) {
    Text(
        text, style = LoopDoType.small,
        modifier = modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(12.dp))
            .background(Color(0xF51E283A), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    )
}

/** Accent then dim, split after the first sentence: `35 → 50 from tomorrow, tue 17 nov.` + ` today and …`. */
@Composable
internal fun NoteText(text: String, modifier: Modifier = Modifier) {
    val cut = text.indexOf(". ").let { if (it < 0) text.length else it + 1 }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = LoopDoColors.accent)) { append(text.substring(0, cut)) }
            append(text.substring(cut))
        },
        style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = modifier,
    )
}

private val ROW = 48.dp
private val CELL = 12.dp
private val STREAK = 40.dp
