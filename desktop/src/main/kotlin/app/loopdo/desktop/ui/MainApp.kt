package app.loopdo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.composed
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import app.loopdo.client.today.countdown
import app.loopdo.desktop.DesktopGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** What a key press does when no text field has it: the rail's letters, `n` add, `w` write. */
fun placeForKey(c: Char): Place? = Place.entries.firstOrNull { it.key == c }

/** The signed-in app: the rail, today (D2) and the places still to come. */
@Composable
fun MainApp(g: DesktopGraph) {
    val today = remember { g.todayViewModel() }
    val journal = remember { g.journalViewModel() }
    val journalUi = remember { JournalBinding(journal, g.photoDir, g.popupDrafts, g.drafts) }
    val jsnack by journal.snack.collectAsState()
    val habits = remember { g.habitsViewModel() }
    val hdata by habits.data.collectAsState()
    val hsnack by habits.message.collectAsState()
    var habitPick by remember { mutableStateOf<String?>(null) }
    var habitEditor by remember { mutableStateOf<String?>(null) } // null = closed, "" = new, else the habit's id
    val stats = remember { g.statsViewModel() }
    val sui by stats.stats.collectAsState()
    val rui by stats.review.collectAsState()
    val smsg by stats.message.collectAsState()
    val statsAct = remember { object : StatsActions {
        override fun setMode(m: app.loopdo.shared.stats.PixelMode) = stats.setMode(m)
        override fun shiftYear(by: Int) = stats.shiftYear(by)
        override fun setLast30(on: Boolean) = stats.setLast30(on)
        override fun openReview() = stats.openReview()
    } }
    val reviewAct = remember { object : ReviewActions {
        override fun page(by: Int) = stats.page(by)
        override fun close() = stats.closeReview()
        override fun addFocus(item: app.loopdo.client.stats.PickItem, goal: Int?) { stats.addFocus(item, goal) }
        override fun removeFocus(id: String) { stats.removeFocus(id) }
    } }
    val tasks = remember { g.tasksViewModel() }
    val tsnack by tasks.message.collectAsState()
    val lists by tasks.lists.collectAsState()
    val templates by tasks.templates.collectAsState()
    var taskEditor by remember { mutableStateOf<String?>(null) } // null = closed, "" = new, else the task's id
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    val taskUi = remember {
        TaskBinding(tasks) { d, ok, err ->
            uiScope.launch {
                try { g.taskActions.saveTask(null, d); ok() }
                catch (e: IllegalArgumentException) { err(e.message ?: "that didn't work.") }
                catch (e: IllegalStateException) { err(e.message ?: "that didn't work.") }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { app.loopdo.client.log.logW("loopdo_tasks", "new task failed", e); err("couldn't save that. try again.") }
            }
        }
    }
    val model by today.state.collectAsState()
    val jm by journal.state.collectAsState()
    val snack by today.snack.collectAsState()
    var place by remember { mutableStateOf(Place.TODAY) }
    var adding by remember { mutableStateOf(false) }
    val status = savedLine(g)
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(place, adding, habitEditor, taskEditor) { if (!Typing.active && !adding && habitEditor == null && taskEditor == null) runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(place) { if (place != Place.STATS) stats.closeReview() }
    Box(Modifier.fillMaxSize().sheetEsc(habitEditor != null || taskEditor != null || (place == Place.STATS && rui != null)) {
        if (habitEditor != null || taskEditor != null) { habitEditor = null; taskEditor = null } else stats.closeReview()
    }
        .rootKeys(rootFocus, adding || habitEditor != null || taskEditor != null) { c ->
        when (c) {
            'n' -> { place = Place.TODAY; taskEditor = "" }
            'w' -> place = Place.JOURNAL
            else -> placeForKey(c)?.let { place = it }
        }
    }) {
        Shell(
            place, { place = it }, status,
            railExtra = { if (place == Place.JOURNAL) jm?.let { OnThisDay(it) } },
            railBottom = {
                if (place == Place.JOURNAL) jm?.let { BasicText("journal streak ${it.streak}d", style = LocalType.current.small) }
                else model?.let { m -> BasicText("⏳ ${m.endsIn.countdown()} left today", style = LocalType.current.small) }
            },
        ) {
            when (place) {
                Place.TODAY -> model?.let { m ->
                    TodayPane(m, today, jm?.today, focusLine(m.habitsOpen + m.habitsDone, m.dueToday + m.late + m.inbox),
                        onWrite = { place = Place.JOURNAL }, onAdd = { taskEditor = "" }, onOpenTask = { taskEditor = it }, onDeleteList = { id -> tasks.deleteList(id) {} },
                        writer = jm?.let { j -> {
                            val v by journalUi.draftVersion.collectAsState()
                            val wd = remember(v, j.today.date) { writerDate(j.today.date, journalUi) }
                            Writer(wd, j.today.date, j.now, if (wd == j.today.date) j.today.mood else null, journalUi, ::pickPhotoFile)
                        } })
                } ?: BasicText("loading today…", style = LocalType.current.small)
                Place.HABITS -> hdata?.let { d ->
                    HabitsPane(d, habitPick, { habitPick = it }, { habitEditor = "" }, { habitEditor = it }, { habits.restore(java.util.UUID.fromString(it)) })
                } ?: BasicText("loading habits…", style = LocalType.current.small)
                Place.STATS -> rui?.let { ReviewPane(it, reviewAct) } ?: sui?.let { st ->
                    val lw by remember { g.thisWeekPromises() }.collectAsState(emptyList<String>() to 0)
                    StatsPane(st, lw.first, lw.second, statsAct)
                } ?: BasicText("loading stats…", style = LocalType.current.small)
                Place.JOURNAL -> jm?.let { JournalPane(it, journalUi, ::pickPhotoFile) } ?: BasicText("loading the journal…", style = LocalType.current.small)
                else -> BasicText("${place.path} arrives in a later step (M10 D8).", style = LocalType.current.small)
            }
        }
        val editing = habitEditor
        if (place == Place.HABITS && editing != null) hdata?.let { d ->
            val row = editing.takeIf { it.isNotEmpty() }?.let(d::habit)
            val start = row?.let { app.loopdo.client.habits.editorStart(it, d.schedules, d.logs, d.today) }
            HabitEditor(start, d.today, archived = row?.archivedOn != null,
                onSave = { draft, onError -> habits.save(row?.id?.let(java.util.UUID::fromString), draft, null, { id -> habitPick = id.toString(); habitEditor = null }, onError) },
                onArchive = { row?.let { habits.archive(java.util.UUID.fromString(it.id)) { habitEditor = null } } },
                onDelete = { row?.let { habits.deleteForever(java.util.UUID.fromString(it.id)) { habitEditor = null; habitPick = null } } },
                onClose = { habitEditor = null })
        }
        // The stats message has its own strip and timer (another message's timer must not clear it).
        if (place == Place.STATS) smsg?.let { m ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp)) { StatsMessage(m) { stats.dismissMessage() } }
        }
        val openTask = taskEditor
        if (place == Place.TODAY && openTask != null) model?.let { m ->
            val detail = openTask.takeIf { it.isNotEmpty() }?.let { id -> remember(id) { tasks.task(id) }.collectAsState(null).value }
            if (openTask.isEmpty() || detail != null) {
                val steps = openTask.takeIf { it.isNotEmpty() }?.let { id -> remember(id) { tasks.subtasks(id) }.collectAsState(emptyList()).value }.orEmpty()
                val seriesRule = detail?.templateId?.let { tid -> templates.firstOrNull { it.id == tid }?.rule }
                androidx.compose.runtime.key(openTask, detail?.id) {
                    TaskEditor(detail, seriesRule, steps, lists, m.date, taskUi) { taskEditor = null }
                }
            }
        }
        if (adding) model?.let { m ->
            AddLine(onDone = { adding = false }) { text, done ->
                today.addTask(text, null, null, null, null, m.date, onSaved = done)
            }
        }
        (snack?.let { it.text to it.id } ?: jsnack?.let { it.text to it.id } ?: hsnack?.let { it.text to it.id } ?: tsnack?.let { it.text to it.id })?.let { (text, id) ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).background(Color(0xF0090F18), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                BasicText(text, style = LocalType.current.small.copy(color = Fern.ink))
            }
            LaunchedEffect(id) { delay(4_000); today.dismissSnack(id); journal.dismiss(id); habits.dismiss(id); tasks.dismiss(id) }
        }
    }
}

/**
 * The rail's letters (`t h j s ,`, `n`, `w`). Handled on the way BACK from the focused element (onKeyEvent, not
 * onPreviewKeyEvent), so a text field that is typing keeps its letters.
 */
fun Modifier.placeKeys(blocked: Boolean, onChar: (Char) -> Unit): Modifier = this.then(
    Modifier.onKeyEvent { e -> handleKey(e, blocked || Typing.active, onChar) },
)

/** Esc closes an open sheet from anywhere (also when no field in it has focus). */
fun Modifier.sheetEsc(open: Boolean, close: () -> Unit): Modifier = this.then(Modifier.onKeyEvent { e ->
    if (open && e.key == Key.Escape && e.type == KeyEventType.KeyDown) { close(); true } else false
})

/** The app's outer box: focusable, so the rail's letters arrive when no field has the keyboard. */
fun Modifier.rootKeys(focus: FocusRequester, blocked: Boolean, onChar: (Char) -> Unit): Modifier =
    this.focusRequester(focus).focusable().placeKeys(blocked, onChar)

/** Whether a text field has the keyboard: the rail's letters stay off then (a typed "t" is a "t"). */
object Typing {
    private val count = java.util.concurrent.atomic.AtomicInteger()
    val active: Boolean get() = count.get() > 0
    fun focus(on: Boolean) { if (on) count.incrementAndGet() else count.updateAndGet { maxOf(0, it - 1) } }
}

/** Put on every text field: tells [Typing] while it has focus. */
fun Modifier.typingField(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (focused) Typing.focus(false) } }
    onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; Typing.focus(it.isFocused) } }
}

private fun handleKey(e: KeyEvent, typing: Boolean, onChar: (Char) -> Unit): Boolean {
    if (typing || e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
    val c = e.utf16CodePoint.toChar()
    if (c !in "thjs,nw") return false
    onChar(c); return true
}

private val KeyEvent.isCtrlPressed get() = nativeKeyEvent.let { (it as? java.awt.event.KeyEvent)?.isControlDown == true }
private val KeyEvent.isAltPressed get() = nativeKeyEvent.let { (it as? java.awt.event.KeyEvent)?.isAltDown == true }
private val KeyEvent.isMetaPressed get() = nativeKeyEvent.let { (it as? java.awt.event.KeyEvent)?.isMetaDown == true }

/** `[+ add]` for now: one line → a task in the inbox (the full task editor comes in D4). Esc closes. */
@Composable
private fun AddLine(onDone: () -> Unit, save: (String, () -> Unit) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0x73050810)), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(top = 130.dp).width(560.dp).background(Color(0xF0090F18), RoundedCornerShape(14.dp)).padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                BasicText("add a task · inbox", style = LocalType.current.label)
                Spacer(Modifier.weight(1f))
                BasicText("esc closes", style = LocalType.current.label)
            }
            Spacer(Modifier.height(6.dp))
            TermField(text, { text = it }, Modifier.focusRequester(focus).onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDone(); true } else false
            }) { if (text.isNotBlank()) save(text.trim()) { text = ""; onDone() } }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The status strip: `✓ all saved · just now`, or what is still waiting to upload. */
@Composable
fun savedLine(g: DesktopGraph): androidx.compose.ui.text.AnnotatedString {
    val queue by remember { g.sync.db.watch("SELECT count(*) FROM ps_crud") { it.getLong(0) ?: 0L } }.collectAsState(emptyList())
    val st by remember { g.sync.db.currentStatus.asFlow() }.collectAsState(g.sync.db.currentStatus)
    val waiting = queue.firstOrNull() ?: 0L
    val last = st.lastSyncedAt?.let { Instant.ofEpochMilli(it.toEpochMilliseconds()) }
    val ago = last?.let { Duration.between(it, Instant.now()).toMinutes() }
    return when {
        waiting > 0 -> savedStatus("$waiting change${if (waiting == 1L) "" else "s"} waiting${if (st.connected) "" else " · offline"}", ok = false)
        ago == null -> savedStatus("all saved")
        ago < 1 -> savedStatus("all saved · just now")
        else -> savedStatus("all saved · $ago min ago")
    }
}

/** The rail's "on this day": the same date in an earlier year, its first line. */
@Composable
private fun OnThisDay(j: app.loopdo.client.journal.JournalModel) {
    val t = LocalType.current
    val d = j.onThisDay.firstOrNull() ?: return
    val first = d.blocks.flatMap { it.items }.filterIsInstance<app.loopdo.client.journal.LogItem.Line>().firstOrNull()?.line?.text ?: return
    Spacer(Modifier.height(24.dp))
    BasicText("on this day", style = t.label)
    Spacer(Modifier.height(8.dp))
    BasicText(d.date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)).lowercase(), style = t.small)
    BasicText(first, style = t.small.copy(color = Fern.ink), modifier = Modifier.padding(top = 4.dp))
}

/** Quick add opens only when the main app would show its screens (D3 review): signed in, not blocked on another
 *  account's unsent changes, and with day settings (else "today" can't be worked out). Otherwise the main window shows. */
fun quickAllowed(signedIn: Boolean, blocked: String?, hasSettings: Boolean): Boolean = signedIn && blocked == null && hasSettings
