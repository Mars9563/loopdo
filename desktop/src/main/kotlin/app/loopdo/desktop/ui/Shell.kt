package app.loopdo.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The five places of the locked rail, with their keys. */
enum class Place(val path: String, val key: Char) {
    TODAY("~/today", 't'), HABITS("~/habits", 'h'), JOURNAL("~/journal", 'j'), STATS("~/stats", 's'), SETTINGS("~/settings", ',')
}

val Wallpaper: ImageBitmap by lazy {
    org.jetbrains.skia.Image.makeFromEncoded(Fern::class.java.getResourceAsStream("/wall-fern.jpg")!!.use { it.readBytes() }).toComposeImageBitmap()
}

/** A dashed 1 px line, the terminal-glass divider. */
fun Modifier.dashedRight(color: Color = Fern.rule) = drawBehind {
    var y = 0f
    while (y < size.height) { drawLine(color, Offset(size.width, y), Offset(size.width, minOf(y + 3f, size.height))); y += 6f }
}

fun Modifier.dashedBottom(color: Color = Fern.rule) = drawBehind {
    var x = 0f
    while (x < size.width) { drawLine(color, Offset(x, size.height), Offset(minOf(x + 3f, size.width), size.height)); x += 6f }
}

/**
 * The window (locked desktop-v1): fern wallpaper fading under the content, a status strip ("✓ all saved"), the left
 * rail with places + keys, the rail's own extra lines ([railExtra]) and the place's content.
 */
@Composable
fun Shell(
    place: Place,
    onPlace: (Place) -> Unit,
    status: AnnotatedString,
    railExtra: @Composable ColumnScope.() -> Unit = {},
    railBottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val t = LocalType.current
    Box(Modifier.fillMaxSize().background(Fern.night)) {
        Image(Wallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = androidx.compose.ui.BiasAlignment(-0.64f, -0.24f))
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Fern.night.copy(alpha = .30f), .22f to Fern.night.copy(alpha = .55f),
                    .45f to Fern.night.copy(alpha = .80f), 1f to Fern.night.copy(alpha = .90f),
                ),
            ),
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(36.dp).background(Color(0x8C070B13)).dashedBottom().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("loopdo_", style = t.small.copy(color = Fern.ink))
                Spacer(Modifier.weight(1f))
                BasicText(status, style = t.small)
            }
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(236.dp).fillMaxHeight()
                        .background(Brush.verticalGradient(listOf(Color(0x590C1420), Color(0x8C070C14))))
                        .dashedRight().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
                ) {
                    BasicText(buildAnnotatedString { append("loopdo"); withStyle(SpanStyle(color = Fern.mint)) { append("_") } }, style = t.mark)
                    Spacer(Modifier.height(22.dp))
                    Place.entries.forEach { p ->
                        val on = p == place
                        Row(
                            Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(9.dp))
                                .background(if (on) Fern.mint.copy(alpha = .10f) else Color.Transparent)
                                .clickable { onPlace(p) }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            BasicText(p.path, style = t.body.copy(color = if (on) Fern.mint else Fern.lichen))
                            BasicText(p.key.toString(), style = t.label.copy(color = if (on) Fern.leaf else Fern.moss))
                        }
                    }
                    railExtra()
                    Spacer(Modifier.weight(1f))
                    railBottom()
                }
                Box(Modifier.fillMaxSize().padding(start = 34.dp, end = 34.dp, top = 24.dp, bottom = 22.dp)) { content() }
            }
        }
    }
}

/** `✓ all saved · just now` with the tick in mint. */
fun savedStatus(text: String, ok: Boolean = true): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = if (ok) Fern.mint else Fern.pollen)) { append(if (ok) "✓ " else "↑ ") }
    append(text)
}
