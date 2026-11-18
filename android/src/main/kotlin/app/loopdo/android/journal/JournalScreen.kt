package app.loopdo.android.journal

import app.loopdo.client.journal.JournalViewModel

import app.loopdo.client.journal.preview

import app.loopdo.client.journal.LogItem
import app.loopdo.client.journal.canAddPhoto
import app.loopdo.client.journal.parseLines

import android.graphics.ImageDecoder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.habits.Fab
import app.loopdo.client.popup.MOODS
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Locked journal-log-v2: `~/journal 🔥 34d` with `[today] | [feed] | [calendar]`.
 * [openWriter]: open today's writer once (the journal reminder's [write], M7), then [onWriterOpened].
 */
@Composable
fun JournalTab(vm: JournalViewModel, openWriter: Boolean = false, onWriterOpened: () -> Unit = {}) {
    val m by vm.state.collectAsStateWithLifecycle()
    val snack by vm.snack.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf("today") }
    // The writer's words live here, not in the sheet: closing it never throws typed text away.
    var writing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var draftLoaded by rememberSaveable { mutableStateOf<String?>(null) }
    var writerError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openWriter) { if (openWriter) { view = "today"; writing = true; writerError = null; onWriterOpened() } }
    /** "date|stored line" being edited, its new text, or deleted. */
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var editText by rememberSaveable { mutableStateOf("") }
    var editError by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingLine by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var openPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var moodFor by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingPhoto by rememberSaveable { mutableStateOf(false) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    /** The calendar's month, and the written day opened from it (null = the grid). */
    var calMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var calDay by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    val model = m
    val today = model?.today?.date
    // The writer's line lives in the shared draft store (also the widget pop-up's), so it survives a tab switch,
    // the app lock and a restart; reloaded when the day changes.
    if (today != null && draftLoaded != today.toString()) { draft = vm.draftFor(today); draftLoaded = today.toString() }
    fun addPhotoFrom(source: () -> ImageDecoder.Source, afterSaved: () -> Unit = {}) {
        val day = today ?: return
        val id = UUID.randomUUID()
        vm.addPhoto(day, afterSaved) { compress(source(), photoFile(ctx, id), id) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { u -> addPhotoFrom({ ImageDecoder.createSource(ctx.contentResolver, u) }) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile?.let(::File)
        if (ok && f != null && f.length() > 0) addPhotoFrom({ ImageDecoder.createSource(f) }, afterSaved = { f.delete() }) else f?.delete()
        cameraFile = null
    }

    BackHandler(enabled = view != "today") { if (calDay != null) calDay = null else view = "today" }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = LoopDoColors.accent)) { append("~/journal") }
                            model?.let { withStyle(SpanStyle(color = LoopDoColors.dim)) { append(" 🔥 ${it.streak}d") } }
                        },
                        style = LoopDoType.body, modifier = Modifier.weight(1f),
                    )
                    listOf("today", "feed", "calendar").forEachIndexed { i, v ->
                        if (i > 0) Text("|", style = LoopDoType.small.copy(color = LoopDoColors.separator))
                        Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Tab) { view = v }.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            Text("[$v]", style = LoopDoType.small.copy(color = if (view == v) LoopDoColors.accent else LoopDoColors.dim))
                        }
                    }
                }
            }
            if (model != null) {
                val edit = { date: LocalDate -> { l: LogItem.Line -> editing = "$date|${l.id}|${l.raw}"; editText = l.line.text; editError = null } }
                val delete = { date: LocalDate -> { l: LogItem.Line -> deletingLine = "$date|${l.id}|${l.raw}" } }
                when (view) {
                    "feed" -> {
                        items(model.onThisDay, key = { "otd-${it.date}" }) { d ->
                            Column(
                                Modifier.padding(vertical = 6.dp).fillMaxWidth()
                                    .background(LoopDoColors.accent.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                                    .border(1.dp, LoopDoColors.accent.copy(alpha = 0.28f), RoundedCornerShape(14.dp)).padding(12.dp),
                            ) {
                                val years = model.today.date.year - d.date.year
                                Text("ON THIS DAY · $years ${if (years == 1) "YEAR" else "YEARS"} AGO", style = LoopDoType.label)
                                DayLog(d.preview(), vm::photo)
                            }
                        }
                        if (model.feed.isEmpty()) item { Hint("nothing written yet. [✎ today] writes the first line.") }
                        items(model.feed, key = { it.date.toString() }) { d ->
                            Column {
                                DayBar(d) { moodFor = d.date.toString() }
                                DayLog(d, vm::photo, edit(d.date), delete(d.date), { openPhoto = it }, { deletingPhoto = it })
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                    "calendar" -> item(key = "calendar") {
                        Column {
                            val open = calDay?.let(LocalDate::parse)
                            if (open == null) {
                                CalendarView(
                                    model, calMonth?.let(java.time.YearMonth::parse) ?: java.time.YearMonth.from(model.today.date),
                                    onMonth = { calMonth = it.toString() }, onDay = { calDay = it.toString() },
                                )
                            } else {
                                Bracket("< calendar", idle = true) { calDay = null }
                                val d = model.feed.firstOrNull { it.date == open }
                                if (d == null) Hint("nothing written on this day.") else {
                                    DayBar(d) { moodFor = d.date.toString() }
                                    DayLog(d, vm::photo, edit(d.date), delete(d.date), { openPhoto = it }, { deletingPhoto = it })
                                }
                            }
                        }
                    }
                    else -> item(key = "today") {
                        Column {
                            DayBar(model.today) { moodFor = model.today.date.toString() }
                            if (model.today.blocks.isEmpty()) Hint("nothing yet today. [✎ today] writes the first line.")
                            DayLog(model.today, vm::photo, edit(model.today.date), delete(model.today.date), { openPhoto = it }, { deletingPhoto = it })
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(140.dp)) }
        }
        if (!writing) Fab("✎ today", Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 14.dp)) { writing = true; writerError = null }
        snack?.let { s ->
            LaunchedEffect(s.id) { delay(3_500); vm.dismiss(s.id) }
            Text(
                s.text, style = LoopDoType.small,
                modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth()
                    .background(Color(0xF51E283A), RoundedCornerShape(12.dp)).padding(14.dp),
            )
        }
        if (writing && model != null) WriterSheet(
            title = "~/journal ${model.today.label}", stamp = model.now.let { String.format(Locale.ROOT, "[%02d:%02d]", it.hour, it.minute) },
            text = draft, onText = { draft = it; writerError = null; vm.keepDraft(model.today.date, it) },
            mood = model.today.mood, onMood = { vm.setMood(model.today.date, it) },
            error = writerError,
            onPhoto = { if (canAddPhoto(model.today)) choosingPhoto = true else writerError = "a day holds 4 photos." },
            onCancel = { writing = false },
            onSave = { vm.addLines(model.today.date, draft, onSaved = { draft = ""; writing = false }, onError = { writerError = it }) },
        )
    }

    if (choosingPhoto) AlertDialog(
        onDismissRequest = { choosingPhoto = false },
        containerColor = Color(0xF51E283A),
        title = { Text("add a photo", style = LoopDoType.title) },
        text = { Text("up to 4 a day. it's kept on this phone until it's uploaded.", style = LoopDoType.small.copy(color = LoopDoColors.dim)) },
        confirmButton = {
            Row {
                Bracket("camera", primary = true) {
                    choosingPhoto = false
                    val f = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
                    cameraFile = f.path
                    camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
                }
                Bracket("gallery", primary = true) {
                    choosingPhoto = false
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        },
        dismissButton = { Bracket("cancel", idle = true) { choosingPhoto = false } },
    )
    editing?.let { key ->
        val (date, lineId, raw) = key.split("|", limit = 3).let { Triple(LocalDate.parse(it[0]), it[1], it[2]) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Color(0xF51E283A),
            title = { Text(parseLines(raw).firstOrNull()?.time?.let { "edit [%02d:%02d]".format(Locale.ROOT, it.hour, it.minute) } ?: "edit", style = LoopDoType.title) },
            text = {
                Column {
                    Box(Modifier.fillMaxWidth().border(1.dp, LoopDoColors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                        BasicTextField(editText, { editText = it; editError = null }, textStyle = LoopDoType.body, cursorBrush = SolidColor(LoopDoColors.accent), modifier = Modifier.fillMaxWidth())
                    }
                    Text("its time stays.", style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(top = 6.dp))
                    editError?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn)) }
                }
            },
            confirmButton = { Bracket("save", primary = true) { vm.editLine(date, lineId, editText, onSaved = { editing = null }, onError = { editError = it }) } },
            dismissButton = { Bracket("cancel", idle = true) { editing = null } },
        )
    }
    deletingLine?.let { key ->
        val (date, lineId, raw) = key.split("|", limit = 3).let { Triple(LocalDate.parse(it[0]), it[1], it[2]) }
        AlertDialog(
            onDismissRequest = { deletingLine = null },
            containerColor = Color(0xF51E283A),
            title = { Text("delete this line?", style = LoopDoType.title) },
            text = { Text(raw, style = LoopDoType.body.copy(color = LoopDoColors.dim)) },
            confirmButton = { Bracket("delete", danger = true) { deletingLine = null; vm.deleteLine(date, lineId) } },
            dismissButton = { Bracket("cancel", idle = true) { deletingLine = null } },
        )
    }
    deletingPhoto?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingPhoto = null },
            containerColor = Color(0xF51E283A),
            title = { Text("delete this photo?", style = LoopDoType.title) },
            text = { Text("it leaves this day.", style = LoopDoType.body.copy(color = LoopDoColors.dim)) },
            confirmButton = { Bracket("delete", danger = true) { deletingPhoto = null; vm.deletePhoto(id) } },
            dismissButton = { Bracket("cancel", idle = true) { deletingPhoto = null } },
        )
    }
    openPhoto?.let { id ->
        val path = model?.let { mm -> (mm.feed + mm.today).firstNotNullOfOrNull { it.localPaths[id] } }
        Dialog(onDismissRequest = { openPhoto = null }) {
            Box(Modifier.clickable { openPhoto = null }) { PhotoThumb(id, path, vm::photo, 340, whole = true) }
        }
    }
    moodFor?.let { key ->
        val date = LocalDate.parse(key)
        AlertDialog(
            onDismissRequest = { moodFor = null },
            containerColor = Color(0xF51E283A),
            title = { Text("mood", style = LoopDoType.title) },
            text = {
                Row {
                    MOODS.forEachIndexed { i, face ->
                        Box(Modifier.heightIn(min = 48.dp).clickable { vm.setMood(date, i + 1); moodFor = null }.padding(8.dp)) { Text(face, fontSize = 22.sp) }
                    }
                }
            },
            confirmButton = { Bracket("clear", idle = true) { vm.setMood(date, null); moodFor = null } },
            dismissButton = { Bracket("cancel", idle = true) { moodFor = null } },
        )
    }
}

@Composable
internal fun Hint(text: String) = Text(text, style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(vertical = 8.dp))


