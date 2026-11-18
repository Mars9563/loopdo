package app.loopdo.android.auth

import app.loopdo.client.auth.AuthProblem
import app.loopdo.client.auth.AuthRepository

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import app.loopdo.android.ui.theme.TerminalField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The `loopdo_` wordmark: accent-blue underscore (owner, 2026-11-15). */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(buildAnnotatedString {
        append("loopdo")
        withStyle(SpanStyle(color = LoopDoColors.accent)) { append("_") }
    }, style = LoopDoType.wordmark, modifier = modifier)
}

private enum class CodeMode { SIGNUP, RESET }

/** Sign-in → (create account | reset) → 6-digit code, as locked in auth-onboarding-v1. */
@Composable
fun AuthFlow(auth: AuthRepository, google: GoogleSignIn) {
    var email by rememberSaveable { mutableStateOf("") }
    var codeMode by rememberSaveable { mutableStateOf<CodeMode?>(null) }
    val mode = codeMode
    if (mode == null) {
        SignInScreen(auth, google, email, onEmail = { email = it }, onCodeSent = { codeMode = it })
    } else {
        CodeScreen(auth, email, mode, onBack = { codeMode = null })
    }
}

@Composable
private fun SignInScreen(
    auth: AuthRepository,
    google: GoogleSignIn,
    email: String,
    onEmail: (String) -> Unit,
    onCodeSent: (CodeMode) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current

    fun run(block: suspend () -> Result<Unit>, then: () -> Unit = {}) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            block().onSuccess { then() }.onFailure { error = it.message }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(64.dp))
        Wordmark()
        Text("a day can only be ticked while it is that day.", style = LoopDoType.small.copy(color = LoopDoColors.dim))
        TerminalField("email", email, onEmail, keyboardType = KeyboardType.Email)
        TerminalField("password", password, { password = it }, password = true, imeAction = ImeAction.Done)
        error?.let { msg ->
            val resetHint = msg.endsWith("[reset].")
            Text(
                msg,
                style = LoopDoType.small.copy(color = LoopDoColors.warn),
                modifier = Modifier.padding(top = 6.dp).then(
                    if (resetHint) Modifier.clickable { run({ auth.sendReset(email) }) { onCodeSent(CodeMode.RESET) } } else Modifier,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        BracketRow(
            BracketItem(if (busy) "..." else "sign in", primary = true, enabled = !busy) { run({ auth.signIn(email, password) }) },
            BracketItem("create account", enabled = !busy) {
                run({ auth.signUp(email, password) }) { onCodeSent(CodeMode.SIGNUP) }
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LoopDoColors.rule)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Bracket("continue with google", enabled = !busy) {
                run({
                    google.idToken(activity).mapCatching { pair ->
                        if (pair != null) auth.signInWithGoogle(pair.first, pair.second).getOrThrow()
                    }.recoverCatching { throw AuthProblem("google sign-in didn't work: ${it.message ?: "try again"}") }
                })
            }
        }
        Spacer(Modifier.height(36.dp))
        Text(
            "your data is stored on this phone first and backed up to your own server.",
            style = LoopDoType.small.copy(color = LoopDoColors.dim),
        )
    }
}

@Composable
private fun CodeScreen(auth: AuthRepository, email: String, mode: CodeMode, onBack: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resendIn by remember { mutableIntStateOf(60) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    LaunchedEffect(resendIn) { if (resendIn > 0) { delay(1000); resendIn -= 1 } }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun verify() {
        if (busy || code.length != 6) return
        busy = true; error = null
        scope.launch {
            val r = if (mode == CodeMode.SIGNUP) auth.verifyCode(email, code) else auth.resetWithCode(email, code, newPassword)
            r.onFailure { error = it.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Bracket("<", idle = true, onClick = onBack)
            Text(if (mode == CodeMode.SIGNUP) "~/verify" else "~/reset", style = LoopDoType.title.copy(color = LoopDoColors.accent))
        }
        Spacer(Modifier.height(24.dp))
        Text("we sent a 6-digit code to")
        Text(email, style = LoopDoType.body.copy(color = LoopDoColors.bright))
        // One hidden field drives six boxes; the code submits after the 6th digit (signup).
        BasicTextField(
            value = code,
            onValueChange = { v ->
                code = v.filter(Char::isDigit).take(6)
                if (code.length == 6 && mode == CodeMode.SIGNUP) verify()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.focusRequester(focus),
            decorationBox = {
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { i ->
                        Box(
                            Modifier.size(width = 40.dp, height = 48.dp).border(
                                1.dp,
                                if (i == code.length) LoopDoColors.accent else LoopDoColors.accent.copy(alpha = 0.35f),
                                RoundedCornerShape(10.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Text(code.getOrNull(i)?.toString() ?: "", style = LoopDoType.title.copy(color = LoopDoColors.bright)) }
                    }
                }
            },
        )
        if (mode == CodeMode.RESET) TerminalField("new password", newPassword, { newPassword = it }, password = true, imeAction = ImeAction.Done)
        Text("it expires in 1 hour. check spam if it's not there.", style = LoopDoType.small.copy(color = LoopDoColors.dim))
        error?.let { Text(it, style = LoopDoType.small.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(12.dp))
        BracketRow(
            BracketItem(if (busy) "..." else if (mode == CodeMode.SIGNUP) "verify" else "set password", primary = true, enabled = !busy && code.length == 6) { verify() },
            BracketItem(if (resendIn > 0) "resend in 0:%02d".format(resendIn) else "resend", idle = resendIn > 0, enabled = resendIn == 0 && !busy) {
                scope.launch {
                    (if (mode == CodeMode.SIGNUP) auth.resendCode(email) else auth.sendReset(email)).onFailure { error = it.message }
                    resendIn = 60
                }
            },
        )
    }
}
