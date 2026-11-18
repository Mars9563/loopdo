package app.loopdo.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.loopdo.client.journal.DayView
import app.loopdo.client.journal.JournalModel
import app.loopdo.client.journal.LogItem
import app.loopdo.client.popup.MOODS
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/** What the journal screen asks of the journal (JournalViewModel; a fake in render tests). */
interface JournalUi {
    fun draftFor(date: LocalDate): String
    fun keepDraft(date: LocalDate, text: String)
    fun addLines(date: LocalDate, input: String, onSaved: () -> Unit, onError: (String) -> Unit)
    fun editLine(date: LocalDate, lineId: String, new: String, onSaved: () -> Unit, onError: (String) -> Unit)
    fun deleteLine(date: LocalDate, lineId: String)
    fun setMood(date: LocalDate, mood: Int?)
    fun addPhoto(date: LocalDate, file: File)
    fun deletePhoto(id: String)
    suspend fun photo(id: String, localPath: String?): File?
}

/** The locked desktop-v1 B: the time-log feed | the mood calendar and the writer. */
@Composable
fun JournalPane(m: JournalModel, ui: JournalUi, pickPhoto: () -> File?) {
    val t = LocalType.current
    var month by remember { mutableStateOf(YearMonth.from(m.today.date)) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val writerFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("~/journal", style = t.title.copy(color = Fern.mint))
            Spacer(Modifier.width(14.dp))
            BasicText(month.month.name.lowercase(), style = t.title.copy(color = Fern.hi))
            Spacer(Modifier.weight(1f))
            BasicText("[✎ write]", style = t.small.copy(color = Fern.mint), modifier = Modifier.clickable { runCatching { writerFocus.requestFocus() } })
        }
        Row(Modifier.fillMaxSize().padding(top = 6.dp)) {
            val days = picked?.let { d -> m.feed.filter { it.date == d }.ifEmpty { listOf(m.today).filter { it.date == d } } } ?: m.feed.ifEmpty { listOf(m.today) }
            Column(Modifier.weight(1.3f).fillMaxHeight().dashedRight().padding(top = 14.dp, end = 26.dp)) {
                if (picked != null) BasicText("[‹ all days]", style = t.small.copy(color = Fern.mint), modifier = Modifier.padding(bottom = 8.dp).clickable { picked = null })
                if (days.isEmpty()) BasicText("nothing written on ${picked?.format(dayFmt)?.lowercase() ?: "this day"}.", style = t.small)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(days, key = { it.date.toString() }) { d -> DayCard(d, ui, last = d == days.last()) }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 14.dp, start = 26.dp)) {
                Label(monthFmt.format(month).lowercase(Locale.ENGLISH), rightContent = {
                    Row {
                        BasicText("[‹ ${month.minusMonths(1).month.name.take(3).lowercase()}]", style = t.label.copy(color = Fern.mint), modifier = Modifier.clickable { month = month.minusMonths(1) })
                        Spacer(Modifier.width(6.dp))
                        val canNext = month < YearMonth.from(m.today.date)
                        BasicText("[${month.plusMonths(1).month.name.take(3).lowercase()} ›]", style = t.label.copy(color = if (canNext) Fern.mint else Fern.moss),
                            modifier = if (canNext) Modifier.clickable { month = month.plusMonths(1) } else Modifier)
                    }
                })
                MoodCalendar(month, m.moods, m.today.date, written = m.feed.map { it.date }.toSet()) { picked = it }
                BasicText("dim to bright: mood 1 to 5 · click a day to read it", style = t.label, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(18.dp))
                Writer(m.today.date, m.now, m.today.mood, ui, pickPhoto, writerFocus)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCard(d: DayView, ui: JournalUi, last: Boolean) {
    val t = LocalType.current
    Column(Modifier.fillMaxWidth().then(if (last) Modifier else Modifier.dashedBottom()).padding(top = 4.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            BasicText(d.date.format(dayFmt).lowercase(Locale.ENGLISH), style = t.body.copy(color = Fern.hi))
            Spacer(Modifier.weight(1f))
            BasicText(listOf(d.moodFace, d.dayTag).filter { it.isNotEmpty() }.joinToString(" "), style = t.small)
        }
        d.blocks.forEachIndexed { bi, block ->
            if (bi > 0) Spacer(Modifier.height(8.dp))
            val photos = block.items.filterIsInstance<LogItem.Photo>()
            block.items.filterIsInstance<LogItem.Line>().forEach { l -> LineRow(d.date, l, ui) }
            if (photos.isNotEmpty()) Row(Modifier.padding(start = 64.dp, top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                photos.forEach { p -> ContextMenuArea(items = { listOf(ContextMenuItem("delete photo") { ui.deletePhoto(p.id) }) }) { Thumb(p.id, d.localPaths[p.id], ui) } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineRow(date: LocalDate, l: LogItem.Line, ui: JournalUi) {
    val t = LocalType.current
    var editing by remember { mutableStateOf(false) }
    var text by remember(l.id, l.line.text) { mutableStateOf(l.line.text) }
    var err by remember { mutableStateOf<String?>(null) }
    ContextMenuArea(items = { listOf(ContextMenuItem("edit") { editing = true }, ContextMenuItem("delete") { ui.deleteLine(date, l.id) }) }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            BasicText(l.line.time?.let { "[${it.format(hm)}]" } ?: "[--:--]", style = t.body.copy(color = Fern.mint))
            Spacer(Modifier.width(12.dp))
            if (!editing) BasicText(l.line.text, style = t.body, modifier = Modifier.weight(1f).clickable { editing = true })
            else Column(Modifier.weight(1f)) {
                val focus = remember { FocusRequester() }
                BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus).border(1.dp, Fern.mint.copy(alpha = .35f), RoundedCornerShape(8.dp)).padding(6.dp)
                    .onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) false
                        else when (it.key) {
                            Key.Escape -> { editing = false; text = l.line.text; true }
                            Key.Enter -> { if (text.isNotBlank()) ui.editLine(date, l.id, text.trim(), { editing = false; err = null }, { e -> err = e }); true }
                            else -> false
                        }
                    }, textStyle = t.body, cursorBrush = SolidColor(Fern.mint))
                BasicText(err ?: "enter saves · esc cancels · its time stays", style = t.label.copy(color = if (err != null) Fern.ember else Fern.lichen))
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
    }
}

@Composable
private fun Thumb(id: String, localPath: String?, ui: JournalUi) {
    val img by produceState<ImageBitmap?>(null, id) {
        value = runCatching { ui.photo(id, localPath)?.readBytes()?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() } }.getOrNull()
    }
    Box(Modifier.size(96.dp, 72.dp).clip(RoundedCornerShape(8.dp)).background(Fern.glass).border(1.dp, Fern.mint.copy(alpha = .18f), RoundedCornerShape(8.dp))) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** The month grid: a day's cell is brighter the better its mood; today outlined; days not yet come are faint. */
@Composable
fun MoodCalendar(month: YearMonth, moods: Map<LocalDate, Int>, today: LocalDate, written: Set<LocalDate>, onDay: (LocalDate) -> Unit) {
    val t = LocalType.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            "mtwtfss".forEach { Box(Modifier.weight(1f).padding(bottom = 2.dp), contentAlignment = Alignment.Center) { BasicText(it.toString(), style = t.label) } }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 7).forEach { i ->
                    val d = week.getOrNull(i)
                    val mood = d?.let { moods[it] }
                    val bg = if (mood != null) Fern.mint.copy(alpha = 0.12f + mood * 0.15f) else Color.Transparent
                    val fg = when { d == null -> Color.Transparent; d > today -> Fern.ink.copy(alpha = .22f); mood != null && mood >= 4 -> Fern.night; mood != null -> Fern.hi; else -> Fern.lichen }
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp)).background(bg)
                            .then(if (d == today) Modifier.border(1.5.dp, Fern.mint, RoundedCornerShape(8.dp)) else Modifier)
                            .then(if (d != null && d <= today) Modifier.clickable { onDay(d) } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { if (d != null) BasicText("${d.dayOfMonth}", style = t.small.copy(color = fg)) }
                }
            }
        }
    }
}

/** The writer card (today): `[HH:mm] what's happening?`, enter saves (shift+enter: new line), photos by picker or drop. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Writer(date: LocalDate, now: LocalTime, mood: Int?, ui: JournalUi, pickPhoto: () -> File?, focus: FocusRequester = remember { FocusRequester() }) {
    val t = LocalType.current
    var text by remember(date) { mutableStateOf(ui.draftFor(date)) }
    var err by remember { mutableStateOf<String?>(null) }
    var dropping by remember { mutableStateOf(false) }
    val save = { if (text.isNotBlank()) ui.addLines(date, text, { text = ""; err = null }, { err = it }) }
    val drop = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropping = true }
            override fun onExited(event: DragAndDropEvent) { dropping = false }
            override fun onEnded(event: DragAndDropEvent) { dropping = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty().map { File(java.net.URI(it)) }
                files.take(4).forEach { ui.addPhoto(date, it) }
                return files.isNotEmpty()
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().background(Color(0x73090F18), RoundedCornerShape(12.dp))
            .border(1.dp, Fern.mint.copy(alpha = if (dropping) .8f else .30f), RoundedCornerShape(12.dp))
            .dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = drop)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            BasicText("write · ${date.format(dayFmt).lowercase(Locale.ENGLISH)}", style = t.label)
            Spacer(Modifier.weight(1f))
            MOODS.forEachIndexed { i, face ->
                BasicText(face, style = t.small.copy(color = if (mood == i + 1) Fern.mint else Fern.lichen), modifier = Modifier.padding(start = 6.dp)
                    .then(if (mood == i + 1) Modifier.border(1.dp, Fern.mint, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { ui.setMood(date, if (mood == i + 1) null else i + 1) }.padding(horizontal = 3.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Box {
            if (text.isEmpty()) BasicText(buildAnnotatedString { withStyle(SpanStyle(color = Fern.mint)) { append("[${now.format(hm)}] ") }; append("what's happening?") }, style = t.body.copy(color = Fern.lichen))
            BasicTextField(text, { text = it; ui.keepDraft(date, it) }, Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                if (it.key == Key.Enter && it.type == KeyEventType.KeyDown && !it.isShiftPressed) { save(); true } else false
            }, textStyle = t.body, cursorBrush = SolidColor(Fern.mint))
        }
        err?.let { BasicText(it, style = t.small.copy(color = Fern.ember), modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("[📷 photo] or drop a file", style = t.small, modifier = Modifier.clickable { pickPhoto()?.let { ui.addPhoto(date, it) } })
            Spacer(Modifier.weight(1f))
            BasicText("[save ↵]", style = t.small.copy(color = Fern.mint), modifier = Modifier.clickable { save() })
        }
    }
}
