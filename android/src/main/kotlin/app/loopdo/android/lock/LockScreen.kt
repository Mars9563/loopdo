package app.loopdo.android.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType

/** The locked app (same world as the not-built-yet screens): `~/locked`, the wordmark line, `[unlock]`. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() } // ask at once; [unlock] asks again after a cancel
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text("~/locked", style = LoopDoType.title.copy(color = LoopDoColors.accent))
        Spacer(Modifier.height(40.dp))
        Text(buildAnnotatedString {
            append("loopdo is locked")
            withStyle(SpanStyle(color = LoopDoColors.accent)) { append("_") }
        }, style = LoopDoType.body.copy(color = LoopDoColors.bright))
        Spacer(Modifier.height(8.dp))
        Text("your fingerprint or the phone's screen lock opens it. the widget keeps working.", style = LoopDoType.small.copy(color = LoopDoColors.dim))
        Spacer(Modifier.height(16.dp))
        Bracket("unlock", primary = true, onClick = onUnlock)
    }
}
