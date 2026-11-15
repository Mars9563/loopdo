package app.loopdo.android.popup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType

/** Mood 1…5 (spec: Limits.MOOD_RANGE); the one place coloured emoji are allowed in controls. */
internal val MOODS = listOf("😞", "😕", "😐", "🙂", "😄")

/** The widget's `[✎ journal]` card (locked widget-v4 pop-up 2, without PHOTOS until M6 per the owner). */
@Composable
fun JournalSheet(
    dateLabel: String,
    streak: Int,
    text: String,
    onText: (String) -> Unit,
    mood: Int?,
    onMood: (Int?) -> Unit,
    error: String?,
    onOpenApp: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { focus.requestFocus() }
    val none = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(none, indication = null, onClick = onDismiss)
            .navigationBarsPadding().imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(14.dp).fillMaxWidth().shadow(20.dp, shape)
                .background(Brush.linearGradient(listOf(Color(0xEB162032), Color(0xEB080C14))), shape)
                .border(1.dp, Color(0x33BED7FF), shape)
                .clickable(none, indication = null) {}
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("~/journal", style = LoopDoType.body.copy(color = LoopDoColors.accent))
                Text(" $dateLabel", style = LoopDoType.body, modifier = Modifier.weight(1f))
                Text("🔥 ${streak}d", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            }
            Text("MOOD", style = LoopDoType.label, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MOODS.forEachIndexed { i, face ->
                    val value = i + 1
                    val on = mood == value
                    Box(
                        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable(role = Role.RadioButton) { onMood(if (on) null else value) }
                            .semantics { contentDescription = "mood $value of 5" },
                        contentAlignment = Alignment.Center,
                    ) {
                        // The chosen face is bracketed in accent mono: [🙂]
                        Text(
                            if (on) "[$face]" else face,
                            style = LoopDoType.body.copy(fontSize = 16.sp, color = LoopDoColors.accent, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                            modifier = if (on) Modifier else Modifier.alpha(0.55f),
                        )
                    }
                }
            }
            DashedRule(Modifier.padding(vertical = 8.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp)
                    .background(Color(0x40000000), RoundedCornerShape(12.dp))
                    .border(1.dp, LoopDoColors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                if (text.isEmpty()) Text("how was today?", style = LoopDoType.body.copy(color = LoopDoColors.done))
                BasicTextField(
                    value = text, onValueChange = onText, textStyle = LoopDoType.body,
                    cursorBrush = SolidColor(LoopDoColors.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            error?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 6.dp)) }
            BracketRow(
                BracketItem("open app ↗", idle = true, onClick = onOpenApp),
                BracketItem("save", primary = true, enabled = text.isNotBlank() || mood != null, onClick = onSave),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
