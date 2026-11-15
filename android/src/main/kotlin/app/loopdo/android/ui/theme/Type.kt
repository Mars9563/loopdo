package app.loopdo.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.loopdo.android.R

/** JetBrains Mono everywhere (owner's choice for the terminal look). Sizes in sp. */
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

object LoopDoType {
    val body = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 22.sp, color = LoopDoColors.text)
    val title = body.copy(fontSize = 16.sp)
    val wordmark = body.copy(fontSize = 34.sp, lineHeight = 40.sp, color = LoopDoColors.bright)
    val small = body.copy(fontSize = 12.sp, lineHeight = 18.sp)
    /** Section labels: HABITS 3/5, EMAIL… */
    val label = body.copy(fontSize = 11.sp, letterSpacing = 1.3.sp, color = LoopDoColors.dim)
    val control = body.copy(fontSize = 14.sp)
}
