package app.loopdo.android.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.shared.stats.PixelMode
import app.loopdo.shared.stats.WeekReview
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The stats tab: locked screen 4, and the weekly review on top of it (locked weekly-review-v1 A/B). */
@Composable
fun StatsTab(vm: StatsViewModel, openReview: Boolean = false, onLinkHandled: () -> Unit = {}) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    LaunchedEffect(openReview, stats != null) { if (openReview && stats != null) { vm.openReview(); onLinkHandled() } }
    BackHandler(enabled = review != null) { vm.closeReview() }
    val r = review
    if (r != null) ReviewScreen(r, vm) else stats?.let { StatsScreen(it, vm) }
}

@Composable
internal fun SectionLabel(left: String, right: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left.uppercase(), style = LoopDoType.label, modifier = Modifier.weight(1f))
        right()
    }
}

@Composable
internal fun KeyValue(key: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = LoopDoType.small, modifier = Modifier.weight(1f))
        value()
    }
}

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

internal fun weekTitle(w: WeekReview): String {
    val end = w.start.plusDays(6)
    val range = if (w.start.month == end.month) "${w.start.dayOfMonth}–${end.format(dayMonth)}" else "${w.start.format(dayMonth)}–${end.format(dayMonth)}"
    return "week ${w.number} · ${range.lowercase()}"
}

internal fun doneLine(w: WeekReview, vsWeek: Boolean = false) = buildAnnotatedString {
    withStyle(SpanStyle(color = LoopDoColors.bright)) { append("${w.done}/${w.total}") }
    withStyle(SpanStyle(color = LoopDoColors.dim)) {
        append(" ${w.percent}%")
        w.deltaPoints?.let { d -> append(" · ${if (d >= 0) "▲" else "▼"}${abs(d)}%"); if (vsWeek) append(" vs ${w.number - 1}") }
    }
}

@Composable
private fun StatsScreen(s: StatsUi, vm: StatsViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("~/stats", style = LoopDoType.title.copy(color = LoopDoColors.accent), modifier = Modifier.weight(1f))
            Bracket("mood", primary = s.mode == PixelMode.MOOD, idle = s.mode != PixelMode.MOOD) { vm.setMode(PixelMode.MOOD) }
            Text("|", style = LoopDoType.small.copy(color = LoopDoColors.separator))
            Bracket("perfect", primary = s.mode == PixelMode.PERFECT, idle = s.mode != PixelMode.PERFECT) { vm.setMode(PixelMode.PERFECT) }
        }
        SectionLabel("year in pixels · ${s.year}") {
            Text("<", style = LoopDoType.small.copy(color = LoopDoColors.accent), modifier = Modifier.clickable(role = Role.Button) { vm.shiftYear(-1) }.padding(12.dp))
            Text(">", style = LoopDoType.small.copy(color = if (s.year < s.today.year) LoopDoColors.accent else LoopDoColors.done),
                modifier = Modifier.clickable(enabled = s.year < s.today.year, role = Role.Button) { vm.shiftYear(1) }.padding(12.dp))
        }
        Pixels(s.pixels, s.mode)
        Text(
            if (s.mode == PixelMode.MOOD) "░ low mood → █ high · blank = no entry" else "█ perfect · ▒ active · blank = neither",
            style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp),
        )
        DashedRule(Modifier.padding(vertical = 8.dp))
        val w = s.week
        SectionLabel(weekTitle(w)) { Bracket("review >", primary = true) { vm.openReview() } }
        KeyValue("done") { Text(doneLine(w), style = LoopDoType.small) }
        w.best?.let { b -> KeyValue("best") { Text(buildAnnotatedString { append("${b.title} "); withStyle(SpanStyle(color = LoopDoColors.bright)) { append("${b.done}/${b.due}") } }, style = LoopDoType.small) } }
        if (w.slipped.isNotEmpty()) KeyValue("slipped") { Text(w.slipped.joinToString(" · "), style = LoopDoType.small.copy(color = LoopDoColors.warn)) }
        DashedRule(Modifier.padding(vertical = 8.dp))
        SectionLabel("mood vs habits") { Text("90 days", style = LoopDoType.label) }
        val rows = s.moodRows
        when {
            rows == null -> Text("shows after 30 days with a mood.", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            rows.isEmpty() -> Text("not enough days on both sides yet.", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            else -> {
                val max = rows.maxOf { abs(it.diff) }.coerceAtLeast(0.1)
                rows.forEach { m ->
                    val label = if (m.label == "no journal") m.label else "${m.label} days"
                    Bar(label, abs(m.diff) / max, (if (m.diff >= 0) "+" else "−") + "%.1f".format(Locale.ENGLISH, abs(m.diff)))
                }
            }
        }
        DashedRule(Modifier.padding(vertical = 8.dp))
        SectionLabel("completion") {
            Bracket("30d", primary = s.last30, idle = !s.last30) { vm.setLast30(true) }
            Bracket("all", primary = !s.last30, idle = s.last30) { vm.setLast30(false) }
        }
        s.completion.forEach { c -> Bar(c.title, c.percent / 100.0, "${c.percent}%") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Bar(label: String, fraction: Double, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LoopDoType.small, modifier = Modifier.width(110.dp), maxLines = 1)
        val blocks = (fraction.coerceIn(0.0, 1.0) * 12).roundToInt()
        Text("█".repeat(blocks), style = LoopDoType.small.copy(color = LoopDoColors.accent), modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, style = LoopDoType.small)
    }
}

@Composable
private fun Pixels(p: Array<IntArray>, mode: PixelMode) {
    val months = "jfmamjjasond"
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
        p.forEachIndexed { m, row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                Text(months[m].toString(), style = LoopDoType.small.copy(fontSize = 9.sp, color = LoopDoColors.dim), modifier = Modifier.width(14.dp))
                row.forEach { v ->
                    val color = when {
                        v == 0 -> Color.White.copy(alpha = 0.05f)
                        mode == PixelMode.MOOD -> LoopDoColors.accent.copy(alpha = 0.15f + 0.85f * (v - 1) / 4f)
                        v == 2 -> LoopDoColors.accent
                        else -> LoopDoColors.accent.copy(alpha = 0.3f)
                    }
                    Box(Modifier.weight(1f).aspectRatio(1f).background(color, RoundedCornerShape(1.5.dp)))
                }
            }
        }
    }
}
