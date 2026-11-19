package app.loopdo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.loopdo.client.settings.SettingsUi
import app.loopdo.client.settings.leadLabel
import app.loopdo.client.settings.nextLead
import app.loopdo.shared.remind.ReminderPrefs
import java.time.LocalTime

/** This laptop's own switches (not synced). */
data class LaptopSettings(val pingsOn: Boolean, val autostartOn: Boolean, val pinOn: Boolean, val email: String)

interface SettingsActions {
    fun rename(name: String)
    fun setPrefs(p: ReminderPrefs)
    fun setPings(on: Boolean)
    fun setAutostart(on: Boolean)
    fun setPin(pin: String)
    fun checkPin(pin: String): Boolean
    fun clearPin()
    fun signOut()
}

/** Where the pin row is: idle, or one of the steps of turning it on / changing it / turning it off. */
private enum class PinStep(val prompt: String) { NEW("new pin"), AGAIN("once more"), OLD_FOR_CHANGE("your pin"), OLD_FOR_OFF("your pin") }

/**
 * ~/settings (laptop), as locked in desktop-screens-v1: name and reminders (the phone's synced profile), this
 * laptop's switches (+ the owner's pin lock row, desktop-pin-v1), and the phone-only things read-only.
 */
@Composable
fun SettingsPane(s: SettingsUi, laptop: LaptopSettings, act: SettingsActions) {
    val t = LocalType.current
    val p = s.prefs
    var editing by remember { mutableStateOf<String?>(null) } // "name" | "journalAt" | "weekAt"
    var pinStep by remember { mutableStateOf<PinStep?>(null) }
    var firstPin by remember { mutableStateOf("") }
    var pinLine by remember { mutableStateOf<String?>(null) }
    var sure by remember { mutableStateOf(false) }

    fun pinTyped(pin: String) {
        when (pinStep) {
            PinStep.NEW -> { firstPin = pin; pinStep = PinStep.AGAIN }
            PinStep.AGAIN -> if (pin == firstPin) { act.setPin(pin); pinStep = null; pinLine = "pin lock on: it asks after an hour away." }
                else { pinStep = PinStep.NEW; pinLine = "they didn't match. once more from the start." }
            PinStep.OLD_FOR_CHANGE -> if (act.checkPin(pin)) { pinStep = PinStep.NEW; pinLine = null } else pinLine = "that's not the pin."
            PinStep.OLD_FOR_OFF -> if (act.checkPin(pin)) { act.clearPin(); pinStep = null; pinLine = "pin lock off." } else pinLine = "that's not the pin."
            null -> Unit
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("~/settings", style = t.title.copy(color = Fern.mint))
            Spacer(Modifier.width(12.dp))
            BasicText("v${s.version}", style = t.small)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Label("you")
                if (editing == "name") EditLine(s.name, onDone = { v -> if (v.isNotBlank() && v != s.name) act.rename(v.trim()); editing = null })
                else SettingRow("name") { Value(s.name.ifBlank { "—" }); Link("[edit]") { editing = "name" } }

                Gap(); Label("reminders · shared with the phone")
                SettingRow("habits + tasks", onClick = { val on = !(p.habits || p.tasks); act.setPrefs(p.copy(habits = on, tasks = on)) }) {
                    Value("10 min before + at"); Switch(p.habits || p.tasks)
                }
                if (editing == "journalAt") EditLine(p.journalAt.toString(), hint = "hh:mm, enter saves", onDone = { v -> parseTime(v)?.let { act.setPrefs(p.copy(journalAt = it)) }; editing = null })
                else SettingRow("journal nudge", onClick = { act.setPrefs(p.copy(journal = !p.journal)) }) {
                    Link(p.journalAt.toString(), value = true) { editing = "journalAt" }; Switch(p.journal)
                }
                SettingRow("day-end warning", onClick = { act.setPrefs(p.copy(dayEnd = !p.dayEnd)) }) {
                    Link("${leadLabel(p.dayEndLead)} before", value = true) { act.setPrefs(p.copy(dayEndLead = nextLead(p.dayEndLead))) }; Switch(p.dayEnd)
                }
                if (editing == "weekAt") EditLine(p.weekReviewAt.toString(), hint = "hh:mm, enter saves", onDone = { v -> parseTime(v)?.let { act.setPrefs(p.copy(weekReviewAt = it)) }; editing = null })
                else SettingRow("sunday review", onClick = { act.setPrefs(p.copy(weekReview = !p.weekReview)) }) {
                    Link(p.weekReviewAt.toString(), value = true) { editing = "weekAt" }; Switch(p.weekReview)
                }

                Gap(); Label("this laptop")
                SettingRow("pings on this laptop", onClick = { act.setPings(!laptop.pingsOn) }) { Switch(laptop.pingsOn) }
                SettingRow("start at login", onClick = { act.setAutostart(!laptop.autostartOn) }) { Switch(laptop.autostartOn) }
                var how by remember { mutableStateOf(false) }
                SettingRow("quick add") { Value("meta+j"); Link("[how]") { how = !how } }
                if (how) BasicText(
                    "system settings → keyboard → shortcuts → add new → command:\n~/.local/bin/loopdo --quick, then press meta+j.",
                    style = t.label.copy(lineHeight = t.label.fontSize * 1.6), modifier = Modifier.padding(bottom = 6.dp),
                )
                SettingRow("pin lock", onClick = { pinLine = null; pinStep = if (laptop.pinOn) PinStep.OLD_FOR_OFF else PinStep.NEW }) {
                    if (laptop.pinOn) { Value("asks after 1h"); Switch(true); Link("[change pin]") { pinLine = null; pinStep = PinStep.OLD_FOR_CHANGE } }
                    else Switch(false)
                }
                pinStep?.let { step ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(step.prompt, style = t.body.copy(color = Fern.lichen), modifier = Modifier.width(110.dp))
                        // A fresh field per step (a reused one kept the last step's digits inside it).
                        androidx.compose.runtime.key(step) {
                            var digits by remember { mutableStateOf("") }
                            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                            androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                            TermField(digits, { v -> digits = v.filter { it in '0'..'9' }.take(4); if (digits.length == 4) pinTyped(digits) },
                                Modifier.width(120.dp).focusRequester(focus), password = true)
                        }
                        Spacer(Modifier.width(10.dp))
                        Link("[cancel]") { pinStep = null; pinLine = null }
                    }
                }
                pinLine?.let { BasicText(it, style = t.small, modifier = Modifier.padding(top = 4.dp)) }
                if (pinStep == PinStep.NEW || pinStep == PinStep.AGAIN) BasicText("4 digits. this laptop only (the phone keeps its own lock).", style = t.label, modifier = Modifier.padding(top = 4.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Label("day", "change on the phone")
                SettingRow("day ends") { Value(s.dayEnd.toString()) }
                SettingRow("time zone") { Value(s.zone.id.lowercase()) }
                s.pending?.let { BasicText(it, style = t.small.copy(color = Fern.pollen), modifier = Modifier.padding(bottom = 4.dp)) }

                Gap(); Label("data")
                SettingRow(if (s.waitingNow) s.data.first else "✓ ${s.data.first}") { s.data.second?.let { Value(it) } }
                SettingRow("export · delete account") { BasicText("on the phone", style = t.small) }

                Gap(); Label("account")
                SettingRow("signed in as ${laptop.email}") {
                    Link(if (sure) "[sign out: sure?]" else "[sign out]", color = Fern.ember) { if (sure) { sure = false; act.signOut() } else sure = true }
                }
                if (sure) BasicText("signing out keeps everything waiting to upload; signing in again sends it.", style = t.label, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private fun parseTime(v: String): LocalTime? = runCatching { LocalTime.parse(v.trim().let { if (it.length == 4) "0$it" else it }) }.getOrNull()

@Composable private fun Gap() = Spacer(Modifier.height(14.dp))

/** One settings row: its name on the left; the whole row toggles when [onClick] is given. */
@Composable
private fun SettingRow(name: String, onClick: (() -> Unit)? = null, right: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(34.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        BasicText(name, style = LocalType.current.body, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { right() }
    }
}

@Composable private fun Value(v: String) = BasicText(v, style = LocalType.current.small.copy(color = Fern.ink))

@Composable private fun Switch(on: Boolean) = BasicText(if (on) "[on]" else "[off]", style = LocalType.current.small.copy(color = if (on) Fern.mint else Fern.moss))

@Composable
private fun Link(label: String, value: Boolean = false, color: androidx.compose.ui.graphics.Color = if (value) Fern.ink else Fern.lichen, onClick: () -> Unit) =
    BasicText(AnnotatedString(label), style = LocalType.current.small.copy(color = color), modifier = Modifier.clickable(onClick = onClick))

@Composable
private fun EditLine(initial: String, hint: String = "enter saves · esc keeps it", onDone: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TermField(v, { v = it }, Modifier.weight(1f), onEnter = { onDone(v) })
        Spacer(Modifier.width(10.dp))
        BasicText(hint, style = LocalType.current.label)
    }
}
