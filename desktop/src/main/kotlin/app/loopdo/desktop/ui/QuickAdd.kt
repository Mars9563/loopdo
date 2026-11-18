package app.loopdo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.loopdo.client.popup.PopupViewModel
import app.loopdo.client.popup.journalDateToOpen
import app.loopdo.desktop.DesktopGraph
import app.loopdo.desktop.quick.QuickItem
import app.loopdo.desktop.quick.parseQuick
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Quick add (locked desktop-screens-v1): one line over whatever you are doing. Text → a journal line stamped now;
 * `t …` → a task in the inbox. Enter saves and closes; esc closes and keeps the draft. A draft started before the day
 * end opens on its own day and is saved there (the phone pop-up's rule, `journalDateToOpen`).
 */
@Composable
fun QuickAdd(g: DesktopGraph, vm: PopupViewModel, onClose: () -> Unit) {
    val t = LocalType.current
    val setup by androidx.compose.runtime.produceState<Triple<java.time.LocalDate, java.time.LocalDate, String>?>(null) {
        val today = g.today()
        value = Triple(today, journalDateToOpen(today, g.drafts.dates().filter { g.drafts.line(it).first.isNotEmpty() }.toSet()), g.stampNow())
    }
    val (today, date, stamp) = setup ?: return
    val earlier = date != today
    var text by remember(date) { mutableStateOf(vm.lineDraft(date).first) }
    val error by vm.error.collectAsState()
    val focus = remember { FocusRequester() }
    val save = {
        when (val q = parseQuick(text)) {
            is QuickItem.Journal -> vm.saveLine(date, q.text, earlier) { onClose() }
            is QuickItem.Task -> vm.addTask(q.title, null, null, null) { vm.keepLineDraft(date, "", g.trustedClock.now().instant, earlier); onClose() }
            null -> Unit
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xF0090F18), RoundedCornerShape(14.dp)).border(1.dp, Fern.mint.copy(alpha = .40f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            BasicText("quick add · ${date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).lowercase(Locale.ENGLISH)}${if (earlier) " (unsaved from before the day ended)" else ""}", style = t.label)
            Spacer(Modifier.weight(1f))
            BasicText("esc closes · draft kept", style = t.label)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().border(1.dp, Fern.mint.copy(alpha = .30f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicText("[$stamp] ", style = t.body.copy(color = Fern.mint, fontSize = t.body.fontSize * 1.1))
            BasicTextField(text, { text = it; vm.keepLineDraft(date, it, g.trustedClock.now().instant, earlier) },
                Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                        Key.Enter -> { save(); true }
                        Key.Escape -> { onClose(); true }
                        else -> false
                    }
                }, textStyle = t.body.copy(fontSize = t.body.fontSize * 1.1), singleLine = true, cursorBrush = SolidColor(Fern.mint))
        }
        Spacer(Modifier.height(8.dp))
        if (error != null) BasicText(error!!, style = t.label.copy(color = Fern.ember))
        else BasicText(buildAnnotatedString {
            append("enter → journal line  ·  "); withStyle(SpanStyle(color = Fern.mint)) { append("t") }; append(" buy milk → task in inbox")
        }, style = t.label)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
