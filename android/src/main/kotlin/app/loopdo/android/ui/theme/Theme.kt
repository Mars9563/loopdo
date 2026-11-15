package app.loopdo.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

private val scheme = darkColorScheme(
    primary = LoopDoColors.accent,
    onPrimary = LoopDoColors.tint,
    background = LoopDoColors.tint,
    surface = LoopDoColors.navBar,
    onSurface = LoopDoColors.text,
    onBackground = LoopDoColors.text,
    error = LoopDoColors.warn,
)

/** The locked world: wallpaper (from the window) under the dark tint, mono text. */
@Composable
fun LoopDoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalTextStyle provides LoopDoType.body) {
            Box(Modifier.fillMaxSize().background(LoopDoColors.tint)) { content() }
        }
    }
}
