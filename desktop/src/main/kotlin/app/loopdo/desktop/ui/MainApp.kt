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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import app.loopdo.client.today.countdown
import app.loopdo.desktop.DesktopGraph
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** What a key press does when no text field has it: the rail's letters, `n` add, `w` write. */
fun placeForKey(c: Char): Place? = Place.entries.firstOrNull { it.key == c }

/** The signed-in app: the rail, today (D2) and the places still to come. */
@Composable
fun MainApp(g: DesktopGraph) {
    val today = remember { g.todayViewModel() }
    val journal = remember { g.journalViewModel() }
    val model by today.state.collectAsState()
    val jm by journal.state.collectAsState()
    val snack by today.snack.collectAsState()
    var place by remember { mutableStateOf(Place.TODAY) }
    var adding by remember { mutableStateOf(false) }
    val status = savedLine(g)
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e -> handleKey(e, adding) { c ->
        when (c) {
            'n' -> { place = Place.TODAY; adding = true }
            'w' -> place = Place.JOURNAL
            else -> placeForKey(c)?.let { place = it }
        }
    } }) {
        Shell(
            place, { place = it }, status,
            railBottom = {
                model?.let { m -> BasicText("⏳ ${m.endsIn.countdown()} left today", style = LocalType.current.small) }
            },
        ) {
            when (place) {
                Place.TODAY -> model?.let { m ->
                    TodayPane(m, today, jm?.today, focusLine(m.habitsOpen + m.habitsDone, m.dueToday + m.late + m.inbox),
                        onWrite = { place = Place.JOURNAL }, onAdd = { adding = true })
                } ?: BasicText("loading today…", style = LocalType.current.small)
                else -> BasicText("${place.path} arrives in a later step (M10 ${when (place) { Place.JOURNAL -> "D3"; Place.HABITS -> "D4"; Place.STATS -> "D5"; else -> "D8" }}).", style = LocalType.current.small)
            }
        }
        if (adding) model?.let { m ->
            AddLine(onDone = { adding = false }) { text, done ->
                today.addTask(text, null, null, null, null, m.date, onSaved = done)
            }
        }
        snack?.let { s ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).background(Color(0xF0090F18), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                BasicText(s.text, style = LocalType.current.small.copy(color = Fern.ink))
            }
            LaunchedEffect(s.id) { delay(4_000); today.dismissSnack(s.id) }
        }
    }
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
