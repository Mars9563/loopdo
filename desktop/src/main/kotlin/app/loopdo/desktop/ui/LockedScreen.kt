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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.loopdo.desktop.lock.PinLock
import app.loopdo.desktop.lock.Unlock
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * ~/locked (desktop-pin-v1, owner 2026-11-19): shown instead of the app when the window is opened more than an hour
 * after the last unlock. The 4th digit opens it (no enter); a wrong pin clears; 5 wrong tries wait 30 s.
 */
@Composable
fun LockedScreen(lock: PinLock, onForgot: suspend () -> Boolean) {
    val t = LocalType.current
    var digits by remember { mutableStateOf("") }
    var line by remember { mutableStateOf<String?>(null) }
    var waitUntil by remember { mutableStateOf<Instant?>(null) }
    val focus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // "forgot?": the pin goes only once the sign-out really happened (offline it fails; removing it then would open
    // the app without the password).
    val forgot: () -> Unit = {
        scope.launch {
            if (onForgot()) lock.clear() else line = "signing out needs the internet. try again when you're online."
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The wait counts down on screen, then the field works again.
    LaunchedEffect(waitUntil) {
        val until = waitUntil ?: return@LaunchedEffect
        while (Instant.now() < until) {
            line = "too many tries · wait ${Duration.between(Instant.now(), until).seconds + 1} s"
            delay(500)
        }
        line = null; waitUntil = null
    }
    val type = { v: String ->
        val d = v.filter { it in '0'..'9' }.take(4)
        if (waitUntil == null) digits = d
        if (digits.length == 4) {
            when (val r = lock.tryUnlock(digits)) {
                Unlock.Ok -> line = null
                is Unlock.Wrong -> line = "wrong pin · ${r.left} ${if (r.left == 1) "try" else "tries"} left"
                is Unlock.Wait -> waitUntil = r.until
            }
            digits = ""
        }
    }
    Box(Modifier.fillMaxSize().background(Fern.night)) {
        Image(Wallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Fern.night.copy(alpha = .55f), Fern.night.copy(alpha = .88f)))))
        Column(
            Modifier.align(Alignment.Center).width(380.dp).background(Color(0xB8090F18), RoundedCornerShape(16.dp))
                .border(1.dp, Fern.mint.copy(alpha = .22f), RoundedCornerShape(16.dp)).padding(horizontal = 30.dp, vertical = 28.dp)
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { runCatching { focus.requestFocus() } },
        ) {
            BasicText(buildAnnotatedString { append("loopdo"); withStyle(SpanStyle(color = Fern.mint)) { append("_") } }, style = t.mark)
            BasicText("~/locked · an hour since you last opened it", style = t.label)
            Spacer(Modifier.height(22.dp))
            BasicText("pin", style = t.label)
            Row(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
                repeat(4) { i ->
                    BasicText(if (i < digits.length) "●" else "_", style = t.mark.copy(color = if (i < digits.length) Fern.mint else Fern.moss), modifier = Modifier.padding(end = 14.dp))
                }
            }
            // The keyboard goes to this field; only the slots above are seen.
            BasicTextField(digits, type, Modifier.focusRequester(focus).typingField().height(1.dp).alpha(0f), singleLine = true)
            line?.let { BasicText(it, style = t.small.copy(color = Fern.ember), modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(12.dp))
            BasicText("type the 4 digits · it opens on the 4th\npings, the tray and quick add keep working while locked", style = t.label.copy(lineHeight = t.label.fontSize * 1.7))
            Spacer(Modifier.height(18.dp))
            BasicText("[forgot? sign in again]", style = t.small.copy(color = Fern.lichen), modifier = Modifier.clickable(onClick = forgot))
        }
    }
}

/**
 * The lock covers the app instead of replacing it (D8 review I2): the app stays composed underneath — an open
 * editor keeps its text, nothing is rebuilt or leaked — but it is invisible, gets no clicks and no keys while locked.
 */
@Composable
fun LockLayer(locked: Boolean, lockScreen: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { locked }.then(if (locked) Modifier.alpha(0f) else Modifier)) { content() }
        if (locked) Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            },
        ) { lockScreen() }
    }
}
