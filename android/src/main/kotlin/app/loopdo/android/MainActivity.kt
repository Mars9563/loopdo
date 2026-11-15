package app.loopdo.android

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.auth.AuthFlow
import app.loopdo.android.today.TodayScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import app.loopdo.android.onboarding.FirstSyncScreen
import app.loopdo.android.onboarding.OnboardingScreen
import app.loopdo.android.onboarding.StartState
import app.loopdo.android.onboarding.hasDaySettings
import app.loopdo.android.onboarding.isOnboarded
import app.loopdo.android.onboarding.localRowCount
import app.loopdo.android.onboarding.startState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import app.loopdo.android.ui.nav.LoopDoNavBar
import app.loopdo.android.ui.nav.Tab
import app.loopdo.android.ui.placeholder.ComingSoon
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoTheme
import app.loopdo.android.ui.theme.LoopDoType
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val graph = (application as LoopDoApp).graph
        val demo = BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
        setContent {
            LoopDoTheme {
                val session by graph.auth.session.collectAsStateWithLifecycle()
                val hasSynced by remember { graph.sync.db.currentStatus.asFlow().map { it.hasSynced } }.collectAsStateWithLifecycle(null)
                val hasSettings by remember { graph.sync.db.hasDaySettings() }.collectAsStateWithLifecycle(false)
                val onboarded by remember { graph.sync.db.isOnboarded() }.collectAsStateWithLifecycle(false)
                val rows by remember { graph.sync.db.localRowCount() }.collectAsStateWithLifecycle(0L)
                if (demo) {
                    DemoApp()
                    return@LoopDoTheme
                }
                val blocked by graph.sync.blocked.collectAsStateWithLifecycle()
                blocked?.let { message ->
                    SwitchRefused(message) { graph.scope.launch { graph.auth.signOut() } }
                    return@LoopDoTheme
                }
                when (startState(session, hasSynced, hasSettings, onboarded)) {
                    StartState.Loading -> Unit // the tinted wallpaper for a moment while the saved session loads
                    StartState.SignedOut -> AuthFlow(graph.auth, graph.google)
                    StartState.SyncingFirstTime -> FirstSyncScreen(rows)
                    StartState.NeedsOnboarding -> OnboardingScreen(graph.onboarding, settingsAlreadySaved = hasSettings)
                    StartState.Ready -> SignedInApp(graph)
                }
            }
        }
    }
}

@Composable
private fun SignedInApp(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val todayVm = viewModel { graph.todayViewModel() }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxSize().statusBarsPadding()) {
            when (tab) {
                Tab.TODAY -> TodayScreen(todayVm) {
                    graph.scope.launch { graph.auth.signOut().onFailure { todayVm.showMessage(it.message ?: "couldn't sign out. try again.") } }
                }
                else -> ComingSoon(tab)
            }
        }
        LoopDoNavBar(selected = tab, onSelect = { tab = it })
    }
}

/** Debug-only look check: the mockup's sample day, no data access. */
@Composable
private fun DemoApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxSize().statusBarsPadding()) {
            if (tab == Tab.TODAY) app.loopdo.android.today.TodayContent(
                remember { app.loopdo.android.today.demoToday() }, null, app.loopdo.android.today.DemoUi, onSignOut = {},
            ) else ComingSoon(tab)
        }
        LoopDoNavBar(selected = tab, onSelect = { tab = it })
    }
}

/**
 * A different account signed in while the previous one still has changes the server hasn't got.
 * Nothing of the previous account is shown or uploaded under the new one; it waits until that
 * account signs in again and syncs (review finding I1). Not mocked: a plain error state.
 */
@Composable
private fun SwitchRefused(message: String, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        Text("~/sync", style = LoopDoType.title.copy(color = LoopDoColors.accent), modifier = Modifier.padding(vertical = 12.dp))
        Text(message, style = LoopDoType.body.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
        BracketRow(BracketItem("sign out", primary = true, onClick = onSignOut))
    }
}
