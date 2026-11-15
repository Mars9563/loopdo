package app.loopdo.android

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.loopdo.android.ui.nav.LoopDoNavBar
import app.loopdo.android.ui.nav.Tab
import app.loopdo.android.ui.placeholder.ComingSoon
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoTheme
import app.loopdo.android.ui.theme.LoopDoType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light system-bar icons over the dark tint.
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent {
            LoopDoTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxSize().statusBarsPadding()) {
                        when (tab) {
                            Tab.TODAY -> Text(
                                "~/today",
                                style = LoopDoType.title.copy(color = LoopDoColors.accent),
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                            ) // the real today screen arrives in Task 9
                            else -> ComingSoon(tab)
                        }
                    }
                    LoopDoNavBar(selected = tab, onSelect = { tab = it })
                }
            }
        }
    }
}
