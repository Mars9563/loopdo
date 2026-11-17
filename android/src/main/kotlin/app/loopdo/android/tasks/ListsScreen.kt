package app.loopdo.android.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.android.ui.theme.TerminalField

/**
 * Locked habits-tasks-v1, screen 7: every list with its open tasks. Tap one to rename or delete it
 * (its tasks go to the inbox; nothing is deleted with it); [+ list] makes a new one.
 */
@Composable
fun ListsScreen(vm: TasksViewModel, onBack: () -> Unit) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    /** The list being edited: its id, "new" for [+ list], or null. */
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Bracket("<", idle = true, onClick = onBack)
            Text("~/lists", style = LoopDoType.title.copy(color = LoopDoColors.accent))
        }
        lists.forEach { l ->
            Row(
                Modifier.fillMaxWidth().height(48.dp).then(
                    if (l.id != null) Modifier.clickable(role = Role.Button) { editing = l.id; name = l.name; error = null } else Modifier,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("# ${l.name}", style = LoopDoType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (l.id == null) "${l.open} · default" else "${l.open}", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            }
        }
        Bracket("+ list", primary = true) { editing = "new"; name = ""; error = null }
        editing?.let { id ->
            val list = lists.firstOrNull { it.id == id }
            DashedRule(Modifier.padding(vertical = 8.dp))
            Text(if (list == null) "NEW LIST" else "RENAME / DELETE # ${list.name}", style = LoopDoType.label)
            TerminalField("name", name, { name = it; error = null })
            if (list != null) Text(
                "deleting a list moves its ${list.open} open ${if (list.open == 1) "task" else "tasks"} to the inbox. nothing is deleted with it.",
                style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(top = 6.dp),
            )
            error?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 6.dp)) }
            val done = { editing = null; name = "" }
            if (list != null) BracketRow(
                BracketItem("delete list", danger = true) { vm.deleteList(id, done) },
                BracketItem("save", primary = true) { vm.saveList(id, name, done) { error = it } },
                modifier = Modifier.padding(top = 6.dp),
            ) else BracketRow(
                BracketItem("cancel", idle = true) { done() },
                BracketItem("save", primary = true) { vm.saveList(null, name, done) { error = it } },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
