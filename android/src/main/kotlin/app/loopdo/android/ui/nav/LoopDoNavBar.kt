package app.loopdo.android.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.LoopDoColors

enum class Tab(val label: String) { TODAY("today"), HABITS("habits"), JOURNAL("journal"), STATS("stats") }

/** `[today] | [habits] | [journal] | [stats]`, the current tab in accent. */
@Composable
fun LoopDoNavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    BracketRow(
        *Tab.entries.map { tab -> BracketItem(tab.label, primary = tab == selected, idle = tab != selected) { onSelect(tab) } }.toTypedArray(),
        modifier = Modifier.background(LoopDoColors.navBar).navigationBarsPadding().padding(vertical = 4.dp),
    )
}
