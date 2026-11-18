package app.loopdo.android.settings

import app.loopdo.client.settings.SettingsViewModel

import app.loopdo.client.settings.Deleting
import app.loopdo.client.settings.deleteEnabled

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.android.ui.theme.TerminalField

/** Locked settings-v1 D (top): what it deletes, export first, type "delete", confirm. */
@Composable
internal fun DeleteScreen(vm: SettingsViewModel, host: SettingsHost, onBack: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Bracket("<", idle = true, onClick = onBack)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = LoopDoColors.accent)) { append("~/settings/") }
                withStyle(SpanStyle(color = LoopDoColors.warn)) { append("delete") }
            }, style = LoopDoType.title)
        }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .border(1.dp, LoopDoColors.warn.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .background(LoopDoColors.warn.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("this deletes everything:", style = LoopDoType.small.copy(color = LoopDoColors.warn))
            Text(
                "every habit, tick, task, journal line, mood and photo, on the server and on every phone, and your sign-in. " +
                    "it can't be undone after 7 days. encrypted server backups keep it up to 13 months, then it's gone.",
                style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.5.sp),
            )
        }
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("1. ", style = LoopDoType.small); Bracket("export now", primary = true, onClick = host.onExport)
            Text("keep a copy first", style = LoopDoType.small.copy(color = LoopDoColors.dim))
        }
        Text("2. type delete:", style = LoopDoType.small)
        TerminalField("confirm", typed, { typed = it.take(20) })
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("3. ", style = LoopDoType.small)
            Bracket("delete in 7 days", danger = true, enabled = deleteEnabled(typed) && !busy) { vm.deleteAccount() }
        }
        message?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn)) }
    }
}

/** Locked settings-v1 D (bottom): for 7 days, on every phone, only this. */
@Composable
fun DeletingScreen(state: Deleting, onKeep: () -> Unit, onExport: () -> Unit, onSignOut: () -> Unit, message: String?) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Spacer(Modifier.height(40.dp))
        Text(buildAnnotatedString { append("loopdo"); withStyle(SpanStyle(color = LoopDoColors.accent)) { append("_") } }, style = LoopDoType.wordmark)
        Spacer(Modifier.height(24.dp))
        Text(if (state.canKeep) "this account will be deleted on ${state.deleteOnLabel}." else "this account is being deleted.", style = LoopDoType.body.copy(color = LoopDoColors.warn))
        if (state.canKeep) Text("until then nothing is lost. sign in on any phone to change your mind.", style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(vertical = 6.dp))
        if (state.canKeep) Bracket("keep my account", primary = true, onClick = onKeep)
        Row {
            Bracket("export now", idle = true, onClick = onExport)
            Spacer(Modifier.weight(1f))
            Bracket("sign out", idle = true, onClick = onSignOut)
        }
        message?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn)) }
    }
}
