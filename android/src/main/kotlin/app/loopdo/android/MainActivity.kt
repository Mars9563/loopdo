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
import app.loopdo.android.auth.AuthState
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
        setContent {
            LoopDoTheme {
                val session by graph.auth.session.collectAsStateWithLifecycle()
                when (session) {
                    AuthState.Loading -> Unit // the tinted wallpaper for a moment while the saved session loads
                    AuthState.SignedOut -> AuthFlow(graph.auth, graph.google)
                    is AuthState.SignedIn -> SignedInApp(onSignOut = { graph.scope.launch { graph.auth.signOut() } })
                }
            }
        }
    }
}

@Composable
private fun SignedInApp(onSignOut: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxSize().statusBarsPadding()) {
            when (tab) {
                Tab.TODAY -> Column(Modifier.padding(horizontal = 22.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("~/today", style = LoopDoType.title.copy(color = LoopDoColors.accent), modifier = Modifier.weight(1f))
                        Bracket("⚙", idle = !settingsOpen) { settingsOpen = !settingsOpen }
                    }
                    // Until the M9 settings screen, [⚙] only offers sign out (locked auth-onboarding-v1).
                    if (settingsOpen) BracketRow(
                        BracketItem("sign out", primary = true) { settingsOpen = false; onSignOut() },
                        BracketItem("cancel", idle = true) { settingsOpen = false },
                    )
                    // The real today screen arrives in Task 9.
                }
                else -> ComingSoon(tab)
            }
        }
        LoopDoNavBar(selected = tab, onSelect = { tab = it })
    }
}
