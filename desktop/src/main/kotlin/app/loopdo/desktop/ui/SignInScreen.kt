package app.loopdo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch

/** A terminal field: 1 px mint-ish outline, 10 dp corners (locked desktop-screens-v1). */
@Composable
fun TermField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, password: Boolean = false, onEnter: () -> Unit = {}) {
    val t = LocalType.current
    BasicTextField(
        value, onChange,
        modifier.typingField().fillMaxWidth().border(1.dp, Fern.mint.copy(alpha = .30f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
            .onPreviewKeyEvent { if (it.key == Key.Enter && it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { onEnter(); true } else false },
        textStyle = t.body, singleLine = true, cursorBrush = SolidColor(Fern.mint),
        visualTransformation = if (password) PasswordVisualTransformation('•') else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

/** First start only: the same account as the phone (locked desktop-screens-v1 "Sign-in"). */
@Composable
fun SignInScreen(signIn: suspend (String, String) -> Result<Unit>, google: (suspend () -> Result<Unit>)? = null) {
    val t = LocalType.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf(false) }
    // One at a time: a Google attempt waits for the browser; the form stays usable once it ends either way.
    val goGoogle = {
        if (google != null && !busy && !waiting) scope.launch {
            waiting = true; error = null
            google().onFailure { error = it.message ?: "that didn't work. try again." }
            waiting = false
        }
    }
    val go = {
        if (!busy && !waiting && email.isNotBlank() && password.isNotEmpty()) scope.launch {
            busy = true; error = null
            signIn(email, password).onFailure { error = it.message ?: "that didn't work. try again." }
            busy = false
        }
    }
    Box(Modifier.fillMaxSize().background(Fern.night)) {
        Image(Wallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Fern.night.copy(alpha = .55f), Fern.night.copy(alpha = .88f)))))
        Column(
            Modifier.align(Alignment.Center).width(440.dp).background(Color(0xB8090F18), RoundedCornerShape(16.dp))
                .border(1.dp, Fern.mint.copy(alpha = .22f), RoundedCornerShape(16.dp)).padding(horizontal = 30.dp, vertical = 28.dp),
        ) {
            BasicText(buildAnnotatedString { append("loopdo"); withStyle(SpanStyle(color = Fern.mint)) { append("_") } }, style = t.mark)
            BasicText("~/setup/sign-in · same account as your phone", style = t.label)
            Spacer(Modifier.height(20.dp))
            BasicText("email", style = t.label)
            TermField(email, { email = it }, Modifier.padding(top = 6.dp, bottom = 14.dp), onEnter = { go() })
            BasicText("password", style = t.label)
            TermField(password, { password = it }, Modifier.padding(top = 6.dp, bottom = 14.dp), password = true, onEnter = { go() })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(if (busy) "[signing in…]" else "[sign in ↵]", style = t.body.copy(color = Fern.mint), modifier = Modifier.clickable { go() })
                Spacer(Modifier.weight(1f))
                if (google != null) BasicText(if (waiting) "finish in your browser…" else "[google ↗] opens your browser",
                    style = t.small.copy(color = if (waiting) Fern.lichen else Fern.mint), modifier = Modifier.clickable { goGoogle() })
            }
            error?.let { BasicText(it.lowercase(), style = t.small.copy(color = Fern.ember), modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(14.dp))
            BasicText("no account yet? make one on the phone first.\nyour data stays on this laptop too, so it works offline.", style = t.label.copy(lineHeight = t.label.fontSize * 1.6))
        }
    }
}

/** A centred one-line state ("restoring your data…", "finish setup on your phone first"). */
@Composable
fun CenterLine(text: String, color: Color = Fern.lichen) = Box(Modifier.fillMaxSize().background(Fern.night), contentAlignment = Alignment.Center) {
    BasicText(text, style = LocalType.current.body.copy(color = color))
}

/** Another account's changes are still on this laptop (the phone's SwitchRefused): the message and a way out. */
@Composable
fun BlockedScreen(message: String, onSignOut: () -> Unit) = Box(Modifier.fillMaxSize().background(Fern.night), contentAlignment = Alignment.Center) {
    val t = LocalType.current
    Column(Modifier.width(520.dp)) {
        BasicText(message, style = t.body.copy(color = Fern.ember))
        Spacer(Modifier.height(16.dp))
        BasicText("[sign out]", style = t.body.copy(color = Fern.mint), modifier = Modifier.clickable(onClick = onSignOut))
    }
}
