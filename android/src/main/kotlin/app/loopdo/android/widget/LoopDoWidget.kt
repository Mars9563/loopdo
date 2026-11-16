package app.loopdo.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import android.content.Intent
import android.net.Uri
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import app.loopdo.android.LoopDoApp
import app.loopdo.android.MainActivity
import app.loopdo.android.R
import java.time.LocalDate

// The locked palette (docs/design/widget/README.md).
internal val TEXT = Color(0xFFDBE6F5)
internal val ACCENT = Color(0xFF9CC4FF)
internal val DIM = Color(0xFF7F8DA3)
internal val DONE = Color(0xFF5F6B7D)
internal val WARN = Color(0xFFFF8A80)
internal val SEPARATOR = Color(0xFF3F4B5E)
internal val RULE = Color(0x2EBED7FF)

/** Height of a habit/task row; the checkbox and `[+1]` fill it. 48 dp = a full finger-sized target (owner, 2026-11-16). */
internal const val ROW_DP = 48
private val PAD = 16.dp

class LoopDoWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as LoopDoApp).graph
        val first = graph.widgetSnapshot()
        provideContent {
            // Follow the data for as long as Glance keeps this session open.
            val state by remember { graph.widgetStates() }.collectAsState(first)
            WidgetContent(state)
        }
    }
}

class LoopDoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LoopDoWidget()
}

private val openApp: Action @Composable get() = actionStartActivity<MainActivity>()

@Composable
private fun WidgetContent(state: WidgetState) {
    val size = LocalSize.current
    Column(
        GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_panel)).cornerRadius(24.dp)
            .padding(horizontal = PAD, vertical = 14.dp),
    ) {
        when (state) {
            is WidgetState.Ready -> Ready(state.model, size)
            WidgetState.SignedOut -> NotReady("sign in to loopdo_")
            WidgetState.Restoring -> NotReady("restoring your data...")
            WidgetState.NeedsSetup -> NotReady("finish setup in the app")
        }
    }
}

/** No checkboxes before the app is ready (ticks could collide with data still being restored). */
@Composable
private fun NotReady(message: String) {
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        MonoText(message, DIM)
        Box(GlanceModifier.height(48.dp).clickable(openApp), contentAlignment = Alignment.CenterStart) {
            MonoText("[open app]", ACCENT, bold = true)
        }
    }
}

@Composable
private fun ColumnScope.Ready(m: WidgetModel, size: DpSize) {
    val inner = size.width - PAD * 2
    // Header: ~/today sat 14 nov ............ ⏳ 3h18m
    Row(GlanceModifier.fillMaxWidth().clickable(openApp), verticalAlignment = Alignment.CenterVertically) {
        MonoText("~/today", ACCENT, sizeSp = 12.5f)
        MonoText(" " + m.dateLabel, TEXT, sizeSp = 12.5f)
        Spacer(GlanceModifier.defaultWeight())
        MonoText("⏳ " + m.countdown, DIM, sizeSp = 12f)
    }
    // Streak line: jrnl 34d · perfect 6d · active 51d ❄2
    Row(GlanceModifier.padding(top = 4.dp, bottom = 8.dp)) {
        val s = m.streaks
        MonoText("jrnl", ACCENT, SMALL_SP); MonoText(" ${s.journal}d · ", TEXT, SMALL_SP)
        MonoText("perfect", ACCENT, SMALL_SP); MonoText(" ${s.perfect}d · ", TEXT, SMALL_SP)
        MonoText("active", ACCENT, SMALL_SP); MonoText(" ${s.active}d" + if (s.freezes > 0) " ❄${s.freezes}" else "", TEXT, SMALL_SP)
    }
    DashedRule(inner)

    val scale = LocalContext.current.resources.configuration.fontScale
    val hints = (if (m.habits.isEmpty()) 1 else 0) + (if (m.tasks.isEmpty()) 1 else 0)
    val fit = fitRows(m.habits.size, m.tasks.size, rowSlots(size.height.value, scale) - hints)
    // Plain rows, no scrolling list: each checkbox keeps its own tap target (see fitRows).
    Column(GlanceModifier.defaultWeight().fillMaxWidth()) {
        Label("HABITS", "${m.habitsDone}/${m.habitsTotal}")
        if (m.habits.isEmpty()) Hint("no habits due today.")
        RowGroups(m.habits.take(fit.habits), m.date, inner)
        Label("TASKS", "${m.tasksOpen}")
        if (m.tasks.isEmpty()) Hint("nothing due today.")
        RowGroups(m.tasks.take(fit.tasks), m.date, inner)
        if (fit.more > 0) Box(GlanceModifier.height(ROW_DP.dp).clickable(openApp), contentAlignment = Alignment.CenterStart) {
            MonoText("+${fit.more} more", DIM)
        }
    }

    // Bottom bar: [+ add]  |  [✎ journal]●  (both open pop-ups over the home screen)
    DashedRule(inner, GlanceModifier.padding(top = 6.dp))
    Row(GlanceModifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity<app.loopdo.android.popup.AddPopupActivity>()), contentAlignment = Alignment.Center) {
            MonoText("[+ add]", ACCENT, bold = true)
        }
        MonoText("|", SEPARATOR)
        Box(GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity<app.loopdo.android.popup.JournalPopupActivity>()), contentAlignment = Alignment.Center) {
            if (m.journalWritten) MonoText("[✓ journal]", TEXT)
            else Row(verticalAlignment = Alignment.CenterVertically) { MonoText("[✎ journal]", TEXT); MonoText("●", WARN) }
        }
    }
}

/** Glance allows at most 10 children per Row/Column, so rows go in groups of 10. */
@Composable
private fun RowGroups(rows: List<WidgetRow>, date: LocalDate, inner: Dp) {
    if (rows.isEmpty()) return
    Column(GlanceModifier.fillMaxWidth()) {
        rows.chunked(10).forEach { chunk -> Column(GlanceModifier.fillMaxWidth()) { chunk.forEach { RowView(it, date, inner) } } }
    }
}

@Composable
private fun Label(left: String, right: String) {
    Row(GlanceModifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
        MonoText(left, DIM, LABEL_SP, letterSpacingEm = 0.12f)
        Spacer(GlanceModifier.defaultWeight())
        MonoText(right, DIM, LABEL_SP, letterSpacingEm = 0.12f)
    }
}

@Composable
private fun Hint(text: String) = Box(GlanceModifier.height(ROW_DP.dp), contentAlignment = Alignment.CenterStart) { MonoText(text, DIM, SMALL_SP) }

/** `[ ] title ........ right [+1]`: only the checkbox ticks; the name opens the app (item screens: M5). */
@Composable
private fun RowView(r: WidgetRow, date: LocalDate, inner: Dp) {
    val color = if (r.done) DONE else TEXT
    val scale = LocalContext.current.resources.configuration.fontScale
    val charDp = BODY_SP * 0.6f * scale // JetBrains Mono advance ≈ 0.6 em
    val rightDp = if (r.right.isEmpty()) 0f else (r.right.length + 1) * charDp
    val titleMax = (inner.value - 48 - rightDp - if (r.plusOne) 48 else 0).coerceAtLeast(40f).dp
    val ctx = LocalContext.current
    fun tap(kind: TapKind) = actionSendBroadcast(
        Intent(ctx, WidgetTapReceiver::class.java).setData(Uri.parse(WidgetTap(kind, r.id, date, r.done).toUri())),
    )
    val check = when {
        !r.isHabit -> tap(TapKind.TASK)
        r.done -> tap(TapKind.UNTICK)
        else -> tap(TapKind.TICK)
    }
    Row(GlanceModifier.fillMaxWidth().height(ROW_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.width(48.dp).fillMaxHeight().clickable(check), contentAlignment = Alignment.CenterStart) {
            MonoText(if (r.done) "[x]" else "[ ]", color)
        }
        Box(GlanceModifier.defaultWeight().fillMaxHeight().clickable(openApp), contentAlignment = Alignment.CenterStart) {
            MonoText(r.title, color, maxWidth = titleMax)
        }
        if (r.right.isNotEmpty()) MonoText(r.right, when (r.rightTone) { Tone.WARN -> WARN; Tone.DIM -> DIM; Tone.TEXT -> if (r.done) DONE else TEXT })
        if (r.plusOne) Box(GlanceModifier.width(48.dp).fillMaxHeight().clickable(tap(TapKind.PLUS)), contentAlignment = Alignment.CenterEnd) {
            MonoText("[+1]", ACCENT)
        }
    }
}
