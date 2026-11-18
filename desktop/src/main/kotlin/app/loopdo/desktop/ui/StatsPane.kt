package app.loopdo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.loopdo.client.stats.StatsUi
import app.loopdo.shared.stats.Cell
import app.loopdo.shared.stats.HabitWeek
import app.loopdo.shared.stats.PixelMode
import app.loopdo.shared.stats.WeekReview
import java.time.LocalDate
import kotlin.math.roundToInt

/** What the stats screen asks for (the phone's StatsViewModel). */
interface StatsActions {
    fun setMode(m: PixelMode)
    fun shiftYear(by: Int)
    fun setLast30(on: Boolean)
    fun openReview()
}

/** The locked desktop-v1 C: the year across the top, this week + promises on the left, mood and completion right. */
@Composable
fun StatsPane(s: StatsUi, lastWeekSaid: List<String>, kept: Int, act: StatsActions) {
    val t = LocalType.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("~/stats", style = t.title.copy(color = Fern.mint))
            Spacer(Modifier.width(14.dp))
            BasicText("${s.year}", style = t.title.copy(color = Fern.hi))
            Spacer(Modifier.weight(1f))
            Choice("mood", s.mode == PixelMode.MOOD) { act.setMode(PixelMode.MOOD) }
            Choice("perfect", s.mode == PixelMode.PERFECT) { act.setMode(PixelMode.PERFECT) }
            BasicText("[‹", style = t.body.copy(color = Fern.lichen), modifier = Modifier.clickable { act.shiftYear(-1) })
            BasicText(" ›]", style = t.body.copy(color = if (s.year < s.today.year) Fern.lichen else Fern.moss), modifier = if (s.year < s.today.year) Modifier.clickable { act.shiftYear(1) } else Modifier)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column {
                YearPixels(s.pixels, s.mode, s.year, s.today)
                BasicText(if (s.mode == PixelMode.MOOD) "year in pixels · dim to bright: mood 1 to 5 · empty: nothing written" else "year in pixels · bright: a perfect day · dim: an active day",
                    style = t.label, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            }
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                val cells = s.pixels.flatMap { it.toList() }.filter { it > 0 }
                Label("${s.year} so far")
                if (s.mode == PixelMode.MOOD) {
                    StatRow("days with a mood", "${cells.size}")
                    StatRow("average mood", if (cells.isEmpty()) "–" else "%.1f".format(java.util.Locale.ENGLISH, cells.average()))
                } else {
                    StatRow("perfect days", "${cells.count { it == 2 }}")
                    StatRow("active days", "${cells.size}")
                }
            }
        }
        Row(Modifier.fillMaxWidth().dashedBottom()) {}
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.weight(1.15f).dashedRight().padding(end = 26.dp)) {
                Label("week ${s.week.number} · ${app.loopdo.client.stats.weekRange(s.week.start)}", rightContent = {
                    BasicText("[review ›]", style = t.label.copy(color = Fern.mint), modifier = Modifier.clickable { act.openReview() })
                })
                WeekSummary(s.week)
                WeekGrid(s.week, emptySet())
                Label("last week you said", if (lastWeekSaid.isEmpty()) "" else "$kept/${lastWeekSaid.size} kept")
                if (lastWeekSaid.isEmpty()) BasicText("no focus was set for this week.", style = t.small)
                lastWeekSaid.forEach { BasicText(it, style = t.body, modifier = Modifier.padding(vertical = 5.dp)) }
            }
            Column(Modifier.weight(1f).padding(start = 26.dp)) {
                Label("mood vs habits · 90 days")
                val rows = s.moodRows
                // The phone's lines and number format (StatsScreen): "gym days … −0.6".
                if (rows == null) BasicText("shows after 30 days with a mood.", style = t.small)
                else if (rows.isEmpty()) BasicText("not enough days on both sides yet.", style = t.small)
                else { val max = rows.maxOf { kotlin.math.abs(it.diff) }.takeIf { it > 0 } ?: 1.0
                    rows.forEach { r ->
                        val label = if (r.label == "no journal") r.label else "${r.label} days"
                        Bar(label, (kotlin.math.abs(r.diff) / max * 100).roundToInt(), negative = r.diff < 0,
                            value = (if (r.diff >= 0) "+" else "\u2212") + "%.1f".format(java.util.Locale.ENGLISH, kotlin.math.abs(r.diff)))
                    } }
                Label("completion", rightContent = {
                    Row { Choice("30d", s.last30) { act.setLast30(true) }; Choice("all", !s.last30) { act.setLast30(false) } }
                })
                if (s.completion.isEmpty()) BasicText("no habits yet.", style = t.small)
                s.completion.forEach { c -> Bar(c.title, c.percent) }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) = ItemRow("", androidx.compose.ui.text.AnnotatedString(label)) { BasicText(value, style = LocalType.current.small.copy(color = Fern.ink)) }

@Composable
fun WeekSummary(w: WeekReview) {
    val t = LocalType.current
    BasicText(buildAnnotatedString {
        append("done "); withStyle(SpanStyle(color = Fern.hi)) { append("${w.done}/${w.total}") }; append(" ${w.percent}%")
        w.deltaPoints?.let { d -> append(" · "); withStyle(SpanStyle(color = if (d >= 0) Fern.mint else Fern.ember)) { append(if (d >= 0) "▲$d%" else "▼${-d}%") }; append(" vs ${w.prevNumber}") }
        append(" · perfect ${w.perfectDays} · active ${w.activeDays}/7")
    }, style = t.small, modifier = Modifier.padding(vertical = 4.dp))
}

/** The week grid with equal squares (the D2-review fix on the mock): done, missed, freeze, not due, still open, `?`. */
@Composable
fun WeekGrid(w: WeekReview, focusIds: Set<String>) {
    val t = LocalType.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Spacer(Modifier.width(132.dp))
        "mtwtfss".forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { BasicText("$it", style = t.label) } }
        Spacer(Modifier.width(44.dp))
    }
    if (w.habits.isEmpty()) BasicText("no habits were due this week.", style = t.small)
    w.habits.forEach { h -> WeekRow(h, w.start, h.id in focusIds) }
    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Cell.DONE to "done", Cell.MISSED to "missed", Cell.FROZEN to "freeze", Cell.OPEN to "open today").forEach { (c, l) ->
            Row(verticalAlignment = Alignment.CenterVertically) { Square(c, false); BasicText(" $l", style = t.label) }
        }
    }
}

@Composable
private fun WeekRow(h: HabitWeek, start: LocalDate, focus: Boolean) {
    val t = LocalType.current
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(buildAnnotatedString { if (focus) withStyle(SpanStyle(color = Fern.mint)) { append("› ") }; append(h.title) }, style = t.body, maxLines = 1, modifier = Modifier.width(132.dp))
        h.cells.forEachIndexed { i, c -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Square(c, start.plusDays(i.toLong()) in h.unverified) } }
        BasicText("${h.done}/${h.due}", style = t.small.copy(color = if (h.due > 0 && h.done < h.due) Fern.ember else Fern.ink), modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun Square(c: Cell, unproven: Boolean) {
    val m = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
    Box(contentAlignment = Alignment.TopEnd) {
        when (c) {
            Cell.DONE -> Box(m.background(Fern.mint))
            Cell.MISSED -> Box(m.border(1.5.dp, Fern.ember, RoundedCornerShape(4.dp)))
            Cell.FROZEN -> Box(m.border(1.5.dp, Fern.mint, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                BasicText("❄︎", style = LocalType.current.label.copy(fontFamily = Glyphs, color = Fern.mint)) }
            Cell.OPEN -> Box(Modifier.size(13.dp).clip(CircleShape).border(1.5.dp, Fern.lichen, CircleShape))
            Cell.NOT_DUE -> Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(3.dp).clip(CircleShape).background(Fern.moss)) }
            Cell.FUTURE -> Box(Modifier.size(16.dp))
        }
        if (unproven) BasicText("?", style = LocalType.current.label.copy(color = Fern.pollen), modifier = Modifier.padding(start = 12.dp))
    }
}

/** 12 rows × 31 fixed squares; month letters on the left. */
@Composable
fun YearPixels(px: Array<IntArray>, mode: PixelMode, year: Int, today: LocalDate) {
    val t = LocalType.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        "jfmamjjasond".forEachIndexed { m, letter ->
            val len = java.time.YearMonth.of(year, m + 1).lengthOfMonth()
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("$letter", style = t.label.copy(fontSize = t.label.fontSize * 0.9f), modifier = Modifier.width(15.dp))
                (0 until 31).forEach { d ->
                    val v = px[m][d]
                    val c = when {
                        d >= len -> Color.Transparent
                        v == 0 -> Fern.ink.copy(alpha = .07f)
                        mode == PixelMode.MOOD -> Fern.mint.copy(alpha = 0.14f + v * 0.17f)
                        v == 2 -> Fern.mint.copy(alpha = .9f)
                        else -> Fern.mint.copy(alpha = .3f)
                    }
                    Box(Modifier.size(17.dp, 13.dp).clip(RoundedCornerShape(2.dp)).background(c))
                }
            }
        }
    }
}

/** A stats message ("pick at most 2.") with its own 4-second timer, apart from the other bottom messages (D5 review). */
@Composable
fun StatsMessage(text: String, onDismiss: () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.background(Color(0xF0090F18), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        BasicText(text, style = LocalType.current.small.copy(color = Fern.ink))
    }
    androidx.compose.runtime.LaunchedEffect(text) { kotlinx.coroutines.delay(4_000); onDismiss() }
}
