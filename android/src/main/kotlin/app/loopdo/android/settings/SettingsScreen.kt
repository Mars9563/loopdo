package app.loopdo.android.settings

import app.loopdo.client.settings.SettingsViewModel

import app.loopdo.client.settings.leadLabel
import app.loopdo.client.settings.nextLead
import app.loopdo.client.settings.versionLabel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.DashedRule
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.android.ui.theme.TerminalField
import java.time.LocalTime

/** What the screen needs from the activity (lock, notifications, sign out and the "save as" picker live there). */
class SettingsHost(
    val lockOn: Boolean, val onToggleLock: () -> Unit,
    val notificationsOff: Boolean, val onAllowNotifications: () -> Unit,
    val onSignOut: () -> Unit, val onExport: () -> Unit,
    /** Owner, 2026-11-19: "Unrestricted" battery (background sync, alarms on time); [allow] reopens Android's dialog. */
    val batteryUnrestricted: Boolean = true, val onAllowBattery: () -> Unit = {},
)

/** Locked settings-v1, screens A/B (and D, the delete confirmation, from [delete account]). */
@Composable
fun SettingsScreen(vm: SettingsViewModel, host: SettingsHost, onClose: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) } // "dayEnd" | "zone" | "name" | "journalAt" | "weekAt" | "delete"
    LaunchedEffect(Unit) { vm.refreshAdmin() }
    BackHandler { if (sheet != null) sheet = null else onClose() }
    val ui = s ?: return
    if (sheet == "delete") return DeleteScreen(vm, host, onBack = { sheet = null })
    if (sheet == "zone") return app.loopdo.android.onboarding.ZonePicker(onPick = { vm.setZone(it); sheet = null }, onBack = { sheet = null }, title = "~/settings/zone")
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Bracket("<", idle = true, onClick = onClose)
                Text("~/settings", style = LoopDoType.title.copy(color = LoopDoColors.accent), modifier = Modifier.weight(1f))
                Text(versionLabel(ui.version), style = LoopDoType.small.copy(color = LoopDoColors.dim))
            }
            Label("data")
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (ui.waitingNow) "↑ " else "✓ ", style = LoopDoType.small.copy(color = if (ui.waitingNow) LoopDoColors.warn else LoopDoColors.accent))
                Text(ui.data.first, style = LoopDoType.small, modifier = Modifier.weight(1f))
                if (!ui.waitingNow) ui.data.second?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.dim)) }
            }
            if (ui.waitingNow) ui.data.second?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp), modifier = Modifier.padding(start = 18.dp, bottom = 4.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Bracket("export now", primary = true, onClick = host.onExport)
                Text("journal, photos, data", style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }

            Label("day")
            Item("day ends at") { Bracket(ui.dayEnd.toString(), primary = true) { sheet = "dayEnd" } }
            Item("time zone") { Text(ui.zone.id.lowercase(), style = LoopDoType.small); Bracket("change", primary = true) { sheet = "zone" } }
            ui.pending?.let { p ->
                Text(p, style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp), modifier = Modifier.padding(start = 18.dp))
                ui.keep?.let { k -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Bracket(k, primary = true) { vm.keepCurrent() } } }
            }
            // Locked mock B: the rule, in one dim line.
            Text("day end: whole hours 00:00–08:00. a change starts when today ends, so today is never cut short or stretched. it needs a connection once.",
                style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
            if (sheet == "dayEnd") FlowRow(Modifier.fillMaxWidth()) {
                (0..8).forEach { h -> Bracket("%02d:00".format(h), primary = h == ui.dayEnd.hour, idle = h != ui.dayEnd.hour) { vm.setDayEnd(h); sheet = null } }
            }

            Label("you")
            if (sheet == "name") NameEditor(ui.name, onSave = { vm.setName(it); sheet = null }, onCancel = { sheet = null })
            else Item("name") { Text(ui.name.ifEmpty { "—" }, style = LoopDoType.small); Bracket("edit", primary = true) { sheet = "name" } }

            Label("reminders", right = "on/off · time")
            val p = ui.prefs
            Item("habits") { Toggle(p.habits) { vm.setPrefs(p.copy(habits = it)) } }
            Item("tasks") { Toggle(p.tasks) { vm.setPrefs(p.copy(tasks = it)) } }
            Item("journal") { Toggle(p.journal) { vm.setPrefs(p.copy(journal = it)) }; Text("at", style = LoopDoType.small); Bracket(p.journalAt.toString(), primary = true) { sheet = "journalAt" } }
            if (sheet == "journalAt") TimeStepper(p.journalAt, onDone = { vm.setPrefs(p.copy(journalAt = it)); sheet = null })
            Item("day end") { Toggle(p.dayEnd) { vm.setPrefs(p.copy(dayEnd = it)) }; Bracket(leadLabel(p.dayEndLead), primary = true) { vm.setPrefs(p.copy(dayEndLead = nextLead(p.dayEndLead))) }; Text("before", style = LoopDoType.small) }
            Item("weekly review") { Toggle(p.weekReview) { vm.setPrefs(p.copy(weekReview = it)) }; Text("sun", style = LoopDoType.small); Bracket(p.weekReviewAt.toString(), primary = true) { sheet = "weekAt" } }
            if (sheet == "weekAt") TimeStepper(p.weekReviewAt, onDone = { vm.setPrefs(p.copy(weekReviewAt = it)); sheet = null })

            Label("app")
            Item("app lock") { Toggle(host.lockOn) { host.onToggleLock() } }
            if (host.notificationsOff) Item("notifications off") { Bracket("allow", primary = true, onClick = host.onAllowNotifications) }
            Item("battery") {
                Text(app.loopdo.android.notify.batteryLine(host.batteryUnrestricted), style = LoopDoType.small)
                if (!host.batteryUnrestricted) Bracket("allow", primary = true, onClick = host.onAllowBattery)
            }

            ui.admin?.let { a ->
                Label("admin", right = "only you see this")
                Item("new sign-ups") { Bracket(a.removeSurrounding("[", "]"), primary = a == "[open]", idle = a != "[open]") { if (a != "[…]") vm.setSignups(a != "[open]") } }
            }

            Label("account")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Bracket("sign out", primary = true, onClick = host.onSignOut)
                Spacer(Modifier.weight(1f))
                Bracket("delete account", danger = true) { vm.dismiss(); sheet = "delete" } // the delete screen starts without an old message
            }
            message?.let { Text(it, style = LoopDoType.small.copy(color = if (it.startsWith("saved") || it.startsWith("the new")) LoopDoColors.accent else LoopDoColors.warn), modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
internal fun Label(text: String, right: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
        Text(text.uppercase(), style = LoopDoType.label, modifier = Modifier.weight(1f))
        right?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp)) }
    }
}

@Composable
private fun Item(label: String, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LoopDoType.small, modifier = Modifier.weight(1f))
        right()
    }
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) = Bracket(if (on) "on" else "off", primary = on, idle = !on) { onChange(!on) }

@Composable
private fun NameEditor(name: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(name) }
    TerminalField("name", text, { text = it.take(60) })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Bracket("cancel", idle = true, onClick = onCancel)
        Bracket("save ↵", primary = true) { onSave(text) }
    }
}

/** A time in 15-minute steps: [−] 21:00 [+]  [done]. */
@Composable
private fun TimeStepper(start: LocalTime, onDone: (LocalTime) -> Unit) {
    var t by rememberSaveable { mutableStateOf(start.toString()) }
    val time = LocalTime.parse(t)
    Row(
        Modifier.fillMaxWidth().background(Color(0x1A9CC4FF), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Bracket("−", primary = true) { t = time.minusMinutes(15).toString() }
        Text(time.toString(), style = LoopDoType.body.copy(color = LoopDoColors.bright))
        Bracket("+", primary = true) { t = time.plusMinutes(15).toString() }
        Spacer(Modifier.weight(1f))
        Bracket("done", primary = true) { onDone(time) }
    }
}
