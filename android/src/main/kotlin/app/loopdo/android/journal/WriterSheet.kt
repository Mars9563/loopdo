package app.loopdo.android.journal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loopdo.android.popup.MOODS
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType

/**
 * Writing a line (locked journal-log-v2, phone 3): the stamp the line will get (`[22:41] · now`), the input, one
 * mood per day, `[📷 photo]` (app only; null in the widget pop-up), `[open app ↗]` (the widget pop-up only, owner
 * 2026-11-18), `[cancel]` `[save ↵]`. Tapping outside or
 * Back calls [onCancel]; the caller keeps the typed text (drafts are never thrown away).
 */
@Composable
fun WriterSheet(
    title: String,
    stamp: String,
    text: String,
    onText: (String) -> Unit,
    mood: Int?,
    onMood: (Int?) -> Unit,
    error: String?,
    onPhoto: (() -> Unit)?,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    notice: String? = null,
    saving: Boolean = false,
    onOpenApp: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { focus.requestFocus() }
    val none = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(none, indication = null, onClick = onCancel)
            .navigationBarsPadding().imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(14.dp).fillMaxWidth().shadow(20.dp, shape)
                .background(Brush.linearGradient(listOf(Color(0xF5162032), Color(0xF5080C14))), shape)
                .border(1.dp, Color(0x33BED7FF), shape)
                .clickable(none, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = LoopDoType.small.copy(color = LoopDoColors.accent), modifier = Modifier.weight(1f))
                MOODS.forEachIndexed { i, face ->
                    val value = i + 1
                    val on = mood == value
                    Box(
                        Modifier.size(40.dp).then(if (on) Modifier.border(1.dp, LoopDoColors.accent, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable(role = Role.RadioButton) { onMood(if (on) null else value) }
                            .semantics { contentDescription = "mood $value of 5" },
                        contentAlignment = Alignment.Center,
                    ) { Text(face, fontSize = 18.sp) }
                }
            }
            notice?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$stamp ", style = LoopDoType.small.copy(color = LoopDoColors.accent))
                Text("· now  ", style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp))
                DashedRule(Modifier.weight(1f))
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .border(1.dp, LoopDoColors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) Text("what's happening?", style = LoopDoType.body.copy(color = LoopDoColors.done))
                // The cursor starts after a kept draft, not before it (M9 manual test).
                var field by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))) }
                androidx.compose.runtime.LaunchedEffect(text) {
                    if (text != field.text) field = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
                }
                BasicTextField(
                    value = field, onValueChange = { field = it; onText(it.text) }, textStyle = LoopDoType.body, cursorBrush = SolidColor(LoopDoColors.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            error?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onPhoto != null) Bracket("📷 photo", idle = true, onClick = onPhoto)
                if (onOpenApp != null) Bracket("open app ↗", idle = true, onClick = onOpenApp)
                Spacer(Modifier.weight(1f))
                Bracket("cancel", idle = true, onClick = onCancel)
                Bracket("save ↵", primary = true, enabled = text.isNotBlank() && !saving, onClick = onSave)
            }
            Text("the time is stamped when you save.", style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp))
        }
    }
}
