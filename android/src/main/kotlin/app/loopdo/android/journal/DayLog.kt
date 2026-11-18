package app.loopdo.android.journal

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.util.Locale

// The locked time log (journal-log-v2): the alt day bar, [HH:mm] lines, thin separators between blocks,
// photos inline at their time.

/**
 * The alt day bar (locked v2, phone 4, owner: in both [today] and [feed]): a fixed-width block, light text on a
 * dim accent block with an accent left edge, then a thin dashed line; `today` dim at the right end.
 */
@Composable
fun DayBar(day: DayView, onMood: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(176.dp).height(30.dp).background(LoopDoColors.accent.copy(alpha = 0.16f))
                .then(if (onMood != null) Modifier.combinedClickable(role = Role.Button, onClickLabel = "set the mood", onClick = onMood) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(LoopDoColors.accent))
            Text(
                day.label + if (day.moodFace.isNotEmpty()) "  ${day.moodFace}" else "",
                style = LoopDoType.small.copy(color = LoopDoColors.bright, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 9.dp),
            )
        }
        Canvas(Modifier.weight(1f).height(1.dp).padding(horizontal = 6.dp)) {
            drawLine(
                LoopDoColors.accent.copy(alpha = 0.45f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
        }
        if (day.dayTag.isNotEmpty()) Text(day.dayTag, style = LoopDoType.small.copy(color = LoopDoColors.dim, fontSize = 11.sp))
    }
}

/** The thin dashed separator between blocks ≥ 30 min apart (much lighter than the day bar). */
@Composable
private fun GapRule() {
    Canvas(Modifier.fillMaxWidth().height(1.dp).padding(vertical = 0.dp)) {
        drawLine(
            LoopDoColors.rule, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )
    }
}

private fun LocalTime.stamp() = String.format(Locale.ROOT, "[%02d:%02d]", hour, minute)

/**
 * A day's lines and photos. Tap a line → [onEditLine]; long-press → [onDeleteLine]. A photo: tap → [onOpenPhoto],
 * long-press → [onDeletePhoto]. Null callbacks make the day read-only (ON THIS DAY).
 */
@Composable
fun DayLog(
    day: DayView,
    photo: suspend (String, String?) -> File?,
    onEditLine: ((LogItem.Line) -> Unit)? = null,
    onDeleteLine: ((LogItem.Line) -> Unit)? = null,
    onOpenPhoto: ((String) -> Unit)? = null,
    onDeletePhoto: ((String) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        day.blocks.forEachIndexed { i, block ->
            if (i > 0) Box(Modifier.padding(vertical = 6.dp)) { GapRule() }
            block.items.forEach { item ->
                when (item) {
                    is LogItem.Line -> LineRow(item, onEditLine, onDeleteLine)
                    is LogItem.Photo -> PhotoItem(item, day.localPaths[item.id], photo, onOpenPhoto, onDeletePhoto)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineRow(item: LogItem.Line, onEdit: ((LogItem.Line) -> Unit)?, onDelete: ((LogItem.Line) -> Unit)?) {
    Text(
        buildAnnotatedString {
            item.line.time?.let { withStyle(SpanStyle(color = LoopDoColors.accent)) { append(it.stamp() + " ") } }
            append(item.line.text)
        },
        style = LoopDoType.body.copy(color = Color(0xFFC3D0E2)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
            .then(
                if (onEdit != null) Modifier.combinedClickable(
                    onClickLabel = "edit", onClick = { onEdit(item) }, onLongClickLabel = "delete", onLongClick = onDelete?.let { { it(item) } },
                ) else Modifier,
            )
            .padding(vertical = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoItem(
    item: LogItem.Photo, localPath: String?, photo: suspend (String, String?) -> File?,
    onOpen: ((String) -> Unit)?, onDelete: ((String) -> Unit)?,
) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item.time?.let { Text(it.stamp(), style = LoopDoType.body.copy(color = LoopDoColors.accent)) }
        PhotoThumb(
            item.id, localPath, photo, 64,
            Modifier.then(
                if (onOpen != null) Modifier.combinedClickable(
                    onClickLabel = "open the photo", onClick = { onOpen(item.id) }, onLongClickLabel = "delete", onLongClick = onDelete?.let { { it(item.id) } },
                ) else Modifier,
            ),
        )
    }
}

/**
 * A photo at [sizeDp] (decoded small, off the main thread); a dim box while it loads or isn't uploaded yet.
 * [whole] = the full photo at [sizeDp] wide in its own shape (the viewer; M6 minor: it was cropped square).
 */
@Composable
fun PhotoThumb(id: String, localPath: String?, photo: suspend (String, String?) -> File?, sizeDp: Int, modifier: Modifier = Modifier, whole: Boolean = false) {
    val key = "$id@$sizeDp"
    var failed by androidx.compose.runtime.remember(id, localPath) { androidx.compose.runtime.mutableStateOf(false) }
    val bitmap by produceState(THUMBS[key], id, localPath) {
        if (value == null) value = withContext(Dispatchers.IO) { photo(id, localPath)?.let { decodeSampled(it, sizeDp * 3) } }?.also { THUMBS[key] = it }
        failed = value == null
    }
    val shape = RoundedCornerShape(8.dp)
    val shown = bitmap
    if (whole && shown != null) {
        Image(
            shown, contentDescription = "photo", contentScale = ContentScale.Fit,
            modifier = modifier.width(sizeDp.dp).aspectRatio(shown.width.toFloat() / shown.height.coerceAtLeast(1)).clip(shape),
        )
        return
    }
    Box(modifier.size(sizeDp.dp).clip(shape).background(LoopDoColors.accent.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = "photo", contentScale = ContentScale.Crop, modifier = Modifier.size(sizeDp.dp)) }
            ?: if (failed && whole) Text("photo not on this phone yet. it loads when you're online.", style = LoopDoType.small.copy(color = LoopDoColors.dim), modifier = Modifier.padding(8.dp))
            else Text(if (failed) "⇣" else "…", style = LoopDoType.small.copy(color = LoopDoColors.dim))
    }
}

/** Decoded photos near the screen (the feed is lazy; a year of photos is never held at once). */
private val THUMBS = Lru<String, ImageBitmap>(64)

/** Decodes [file] at about [px] on the short edge (power-of-two sampling), or null if it can't be read. */
fun decodeSampled(file: File, px: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

