package app.loopdo.android.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loopdo.android.habits.short
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The journal [calendar] (locked README v2, screen 3; same world as the habit heatmap): a month grid, each
 * written day filled by its mood (dim → bright), an outline when it has no mood; tap a written day to read it.
 */
@Composable
fun CalendarView(model: JournalModel, month: YearMonth, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val today = model.today.date
    val days = model.feed.associate { it.date to it.mood }
    val first = model.feed.minOfOrNull { it.date }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val prev = month.minusMonths(1)
            val next = month.plusMonths(1)
            Bracket("< ${prev.shortName()}", idle = true, enabled = canGoBack(month, first)) { onMonth(prev) }
            Text(
                month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase(Locale.ENGLISH) + " " + month.year,
                style = LoopDoType.label.copy(color = LoopDoColors.text), textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Bracket("${next.shortName()} >", idle = true, enabled = canGoForward(month, today)) { onMonth(next) }
        }
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach {
                Text(it.short().take(1), textAlign = TextAlign.Center, style = LoopDoType.label, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            }
        }
        val cells = journalMonth(days, month, today)
        val lead = month.atDay(1).dayOfWeek.value - 1
        (List(lead) { null } + cells + List((7 - (lead + cells.size) % 7) % 7) { null }).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { c -> Box(Modifier.weight(1f).padding(3.dp).aspectRatio(1f)) { c?.let { Cell(it, onDay) } } }
            }
        }
        Text("dim → bright = mood 1 → 5 · outline = no mood · tap a day to read it", style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp), modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun Cell(c: CalDay, onDay: (LocalDate) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val shade = moodShade(c.mood)
    val box = Modifier.fillMaxSize()
        .then(if (c.hasEntry && shade > 0f) Modifier.background(LoopDoColors.accent.copy(alpha = shade), shape) else Modifier)
        .then(if (c.hasEntry && shade == 0f) Modifier.border(1.dp, LoopDoColors.accent.copy(alpha = 0.6f), shape) else Modifier)
        .then(if (c.today) Modifier.border(1.5.dp, LoopDoColors.accent, shape) else Modifier)
        .then(if (c.hasEntry) Modifier.clickable(role = Role.Button, onClickLabel = "read this day") { onDay(c.date) } else Modifier)
        .then(if (c.future) Modifier.alpha(0.35f) else Modifier)
    Box(box, contentAlignment = Alignment.Center) {
        Text(
            c.date.dayOfMonth.toString(),
            style = LoopDoType.small.copy(color = when {
                c.hasEntry && shade > 0.5f -> Color(0xFF0B1220)
                c.hasEntry -> LoopDoColors.text
                else -> LoopDoColors.dim
            }),
        )
    }
}

private fun YearMonth.shortName() = month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase(Locale.ENGLISH)
