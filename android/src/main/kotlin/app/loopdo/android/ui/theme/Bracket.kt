package app.loopdo.android.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A terminal-style control: the text `[label]` is the whole visual, while the touch
 * target stays at least 48 dp (owner's design + Android's minimum).
 */
@Composable
fun Bracket(label: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, idle: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "[$label]",
            style = LoopDoType.control.copy(
                color = when {
                    !enabled -> LoopDoColors.done
                    primary -> LoopDoColors.accent
                    idle -> LoopDoColors.dim
                    else -> LoopDoColors.text
                },
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/** [idle] = a not-selected choice (e.g. an inactive tab): shown dim, as in the locked design. */
data class BracketItem(val label: String, val primary: Boolean = false, val enabled: Boolean = true, val idle: Boolean = false, val onClick: () -> Unit)

/** `[a] | [b] | [c]` with dim separators, spread across the row. */
@Composable
fun BracketRow(vararg items: BracketItem, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, item ->
            if (i > 0) Text("|", style = LoopDoType.control.copy(color = LoopDoColors.separator))
            Bracket(item.label, primary = item.primary, enabled = item.enabled, idle = item.idle, onClick = item.onClick)
        }
    }
}
