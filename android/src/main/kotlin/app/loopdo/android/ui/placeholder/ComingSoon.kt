package app.loopdo.android.ui.placeholder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import app.loopdo.android.ui.nav.Tab
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType

/** Locked "not built yet" screen (auth-onboarding-v1, screen 5). */
@Composable
fun ComingSoon(tab: Tab) {
    val (milestone, what) = when (tab) {
        Tab.HABITS -> "M5" to "the habits list, heatmaps and the habit editor arrive in the next builds (M5). your habits already sync, and you can tick them on [today]."
        Tab.JOURNAL -> "M6" to "the journal feed, calendar, photos and on-this-day arrive in the next builds (M6). journal lines you add from [+ add] are already saved."
        Tab.STATS -> "M8" to "year in pixels, the weekly review and mood vs habits arrive in a later build (M8)."
        Tab.TODAY -> "" to ""
    }
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text("~/${tab.label}", style = LoopDoType.title.copy(color = LoopDoColors.accent))
        Spacer(Modifier.height(40.dp))
        Text(buildAnnotatedString {
            append("not built yet")
            withStyle(SpanStyle(color = LoopDoColors.accent)) { append("_") }
        }, style = LoopDoType.body.copy(color = LoopDoColors.bright))
        Spacer(Modifier.height(8.dp))
        Text(what, style = LoopDoType.small.copy(color = LoopDoColors.dim))
        if (milestone.isEmpty()) Unit
    }
}
