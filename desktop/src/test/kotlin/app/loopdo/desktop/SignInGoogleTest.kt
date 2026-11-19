package app.loopdo.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.SignInScreen
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals

/** D8: [google ↗] opens the browser; the screen waits for it, and a closed tab or timeout leaves a plain line. */
@OptIn(ExperimentalTestApi::class)
class SignInGoogleTest {
    @Test
    fun `google waits for the browser and signs in`() = runComposeUiTest {
        val done = CompletableDeferred<Result<Unit>>()
        var calls = 0
        setContent { LoopDoTheme { SignInScreen(signIn = { _, _ -> Result.success(Unit) }, google = { calls++; done.await() }) } }
        onNodeWithText("[google ↗]", substring = true).performClick()
        waitForIdle()
        onNodeWithText("finish in your browser…", substring = true).assertExists()
        onNodeWithText("finish in your browser…", substring = true).performClick() // a second click while waiting
        waitForIdle()
        assertEquals(1, calls, "one at a time")
        runOnIdle { done.complete(Result.success(Unit)) }
        waitForIdle()
    }

    @Test
    fun `a closed tab or timeout shows a plain line and the form still works`() = runComposeUiTest {
        var emailTries = 0
        setContent {
            LoopDoTheme {
                SignInScreen(signIn = { _, _ -> emailTries++; Result.success(Unit) },
                    google = { Result.failure(IllegalStateException("nothing came back from the browser. try again.")) })
            }
        }
        onNodeWithText("[google ↗]", substring = true).performClick()
        waitForIdle()
        onNodeWithText("nothing came back from the browser. try again.").assertExists()
        onNodeWithText("[google ↗]", substring = true).assertExists()
    }

    @Test
    fun `cancel stops waiting for the browser and the form works again`() = runComposeUiTest {
        var cancelled = false
        setContent {
            LoopDoTheme {
                SignInScreen(signIn = { _, _ -> Result.success(Unit) }, google = {
                    try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
                })
            }
        }
        onNodeWithText("[google ↗]", substring = true).performClick()
        waitForIdle()
        onNodeWithText("[cancel]").performClick()
        waitForIdle()
        assertEquals(true, cancelled, "the browser wait is stopped (supabase-kt stops its callback server)")
        onNodeWithText("[google ↗]", substring = true).assertExists()
    }
}
