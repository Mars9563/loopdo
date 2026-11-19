package app.loopdo.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/** The fern palette, exactly the locked desktop-v1 tokens (sampled from wall-fern.jpg). */
object Fern {
    val night = Color(0xFF0B111D)
    val leaf = Color(0xFF3E9D73)
    val mint = Color(0xFF8FE3B8)
    val ink = Color(0xFFD4EEE2)
    val hi = Color(0xFFF1FBF6)
    val lichen = Color(0xFF7D9B90)
    val moss = Color(0xFF4C665E)
    val ember = Color(0xFFFF9F87)
    val pollen = Color(0xFFF2CF7C)
    val rule = Color(0x298FE3B8) // rgba(143,227,184,.16)
    val glass = Color(0x940A0F18)
}

private fun fontBytes(name: String): ByteArray =
    Fern::class.java.getResourceAsStream("/font/$name")!!.use { it.readBytes() }

/** JetBrains Mono (OFL), the phone's two weights; the mockup's 500 uses semibold. */
val Mono: FontFamily by lazy {
    FontFamily(
        Font("jbm-regular", fontBytes("jetbrains_mono_regular.ttf"), FontWeight.Normal),
        Font("jbm-semibold", fontBytes("jetbrains_mono_semibold.ttf"), FontWeight.SemiBold),
    )
}

/** Symbols JetBrains Mono lacks (❄): bundled DejaVu Sans Mono (free licence, see font/DEJAVU-LICENSE.txt), so they stay text, not colour emoji. */
val Glyphs: FontFamily by lazy { FontFamily(Font("dejavu-mono", fontBytes("dejavu_sans_mono.ttf"), FontWeight.Normal)) }

/** Type scale from desktop-v1: body 13.5, small 12.5, label 11.5, path/date 22, mark 24. */
class LoopDoType(
    val body: TextStyle = TextStyle(fontFamily = Mono, fontSize = 13.5.sp, color = Fern.ink, lineHeight = 21.sp),
    val small: TextStyle = body.copy(fontSize = 12.5.sp, color = Fern.lichen),
    val label: TextStyle = body.copy(fontSize = 11.5.sp, color = Fern.lichen),
    val title: TextStyle = body.copy(fontSize = 22.sp, lineHeight = 28.sp),
    val mark: TextStyle = body.copy(fontSize = 24.sp, color = Fern.hi, lineHeight = 30.sp),
)

val LocalType = staticCompositionLocalOf { LoopDoType() }

/** The fern theme; [zoom] scales everything drawn inside (the app's zoom, see UiZoom; 1 in tests and renders). */
@Composable
fun LoopDoTheme(zoom: Float = 1f, content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(
        LocalType provides LoopDoType(),
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * zoom, d.fontScale),
        content = content,
    )
}
