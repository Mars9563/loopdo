package app.loopdo.android.onboarding

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.loopdo.android.auth.Wordmark
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.android.ui.theme.TerminalField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Locked auth-onboarding-v1 screen 3: zone, day end (whole hours), name. Once, can't be skipped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onboarding: Onboarding) {
    var zone by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    var dayEndHour by rememberSaveable { mutableStateOf(4) }
    var name by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }

    if (picking) {
        ZonePicker(onPick = { zone = it; picking = false }, onBack = { picking = false })
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("~/setup", style = LoopDoType.title.copy(color = LoopDoColors.accent), modifier = Modifier.weight(1f))
            Text("1 time only", style = LoopDoType.small.copy(color = LoopDoColors.dim))
        }
        Text("TIME ZONE", style = LoopDoType.label, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().border(1.dp, LoopDoColors.accent.copy(alpha = 0.18f), RoundedCornerShape(12.dp)).padding(start = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(zone, modifier = Modifier.weight(1f))
            if (zone == ZoneId.systemDefault().id) Text("· detected", style = LoopDoType.small.copy(color = LoopDoColors.dim))
            Bracket("change", primary = true) { picking = true }
        }
        Text("MY DAY ENDS AT", style = LoopDoType.label, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            (0..8).forEach { h -> Bracket("%02d:00".format(h), primary = h == dayEndHour, idle = h != dayEndHour) { dayEndHour = h } }
        }
        Text(
            Onboarding.example(now, ZoneId.of(zone), LocalTime.of(dayEndHour, 0)),
            style = LoopDoType.small.copy(color = LoopDoColors.dim),
            modifier = Modifier.padding(top = 4.dp),
        )
        TerminalField("name", name, { name = it }, imeAction = ImeAction.Done)
        Text(
            "you can change these later in [⚙]. changes start when your current day ends, so a closed day never reopens.",
            style = LoopDoType.small.copy(color = LoopDoColors.dim),
            modifier = Modifier.padding(top = 10.dp),
        )
        error?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Bracket(if (busy) "..." else "start", primary = true, enabled = !busy) {
                busy = true; error = null
                scope.launch {
                    onboarding.submit(ZoneId.of(zone), LocalTime.of(dayEndHour, 0), name).onFailure { error = it.message }
                    busy = false // on success the synced settings row moves the app on
                }
            }
        }
    }
}

@Composable
private fun ZonePicker(onPick: (String) -> Unit, onBack: () -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val results = remember(q) { if (q.isBlank()) Zones.all() else Zones.search(q) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Bracket("<", idle = true, onClick = onBack)
            Text("~/setup/zone", style = LoopDoType.title.copy(color = LoopDoColors.accent))
        }
        TerminalField("search", q, { q = it }, imeAction = ImeAction.Search)
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(results) { z ->
                Text(z, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onPick(z) }.padding(vertical = 12.dp))
            }
        }
    }
}

/** Locked screen 4: shown until the first sync finishes (nothing tickable before). */
@Composable
fun FirstSyncScreen(rows: Long) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.Center) {
        Wordmark()
        Text("restoring your data...")
        Spacer(Modifier.height(8.dp))
        val filled = (System.currentTimeMillis() / 400 % 11).toInt()
        Text(buildString { repeat(10) { append(if (it < filled) '■' else '□') } } + "  $rows rows", style = LoopDoType.small.copy(color = LoopDoColors.accent))
        Spacer(Modifier.height(14.dp))
        Text(
            "nothing can be ticked until this finishes, so nothing you do now can clash with your restored history.",
            style = LoopDoType.small.copy(color = LoopDoColors.dim),
        )
        Spacer(Modifier.height(10.dp))
        Text("no connection? it continues when you're back online.", style = LoopDoType.small.copy(color = LoopDoColors.dim))
    }
}
