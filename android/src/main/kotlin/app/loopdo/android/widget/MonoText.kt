package app.loopdo.android.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.layout.size
import androidx.glance.unit.ColorProvider
import app.loopdo.android.R
import kotlin.math.ceil
import kotlin.math.max

/*
 * Why the widget draws its own text: on the owner's A55 (One UI 9) the launcher draws every
 * widget TextView in Samsung's system font, ignoring bundled fonts and even "monospace"
 * (M4 Task 1). So each run of text is painted with the bundled JetBrains Mono into an
 * ALPHA_8 image (1 byte per pixel) and tinted by the launcher.
 */

internal const val BODY_SP = 13f
internal const val SMALL_SP = 11.5f
internal const val LABEL_SP = 10.5f

internal object MonoPainter {
    private fun paint(ctx: Context, sizeSp: Float, bold: Boolean, letterSpacingEm: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ctx.resources.getFont(if (bold) R.font.jetbrains_mono_semibold else R.font.jetbrains_mono_regular)
        // Scaled by the phone's font-size setting, like real text.
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, ctx.resources.displayMetrics)
        letterSpacing = letterSpacingEm
    }

    /** [text] as an alpha mask; cut with `…` to fit [maxWidthPx]. */
    fun text(ctx: Context, text: String, sizeSp: Float, bold: Boolean = false, letterSpacingEm: Float = 0f, maxWidthPx: Int? = null): Bitmap {
        val p = paint(ctx, sizeSp, bold, letterSpacingEm)
        val shown = if (maxWidthPx != null) TextUtils.ellipsize(text, p, maxWidthPx.toFloat(), TextUtils.TruncateAt.END).toString() else text
        val fm = p.fontMetricsInt
        val bm = Bitmap.createBitmap(max(1, ceil(p.measureText(shown)).toInt()), max(1, fm.descent - fm.ascent), Bitmap.Config.ALPHA_8)
        Canvas(bm).drawText(shown, 0f, -fm.ascent.toFloat(), p)
        return bm
    }

    /** A 1 dp dashed line [widthPx] wide (the locked dashed separator). */
    fun dashes(ctx: Context, widthPx: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val bm = Bitmap.createBitmap(max(1, widthPx), max(1, d.toInt()), Bitmap.Config.ALPHA_8)
        val p = Paint().apply { strokeWidth = d; pathEffect = DashPathEffect(floatArrayOf(3 * d, 3 * d), 0f) }
        Canvas(bm).drawLine(0f, d / 2, widthPx.toFloat(), d / 2, p)
        return bm
    }
}

/** A run of JetBrains Mono text in the widget, read out by screen readers as [text]. */
@Composable
internal fun MonoText(
    text: String, color: Color, sizeSp: Float = BODY_SP, bold: Boolean = false, letterSpacingEm: Float = 0f,
    maxWidth: Dp? = null, modifier: GlanceModifier = GlanceModifier,
) {
    val ctx = LocalContext.current
    val d = ctx.resources.displayMetrics.density
    val bm = MonoPainter.text(ctx, text, sizeSp, bold, letterSpacingEm, maxWidth?.let { (it.value * d).toInt() })
    Image(
        ImageProvider(bm), contentDescription = text, colorFilter = ColorFilter.tint(ColorProvider(color)),
        modifier = modifier.size((bm.width / d).dp, (bm.height / d).dp),
    )
}

@Composable
internal fun DashedRule(width: Dp, modifier: GlanceModifier = GlanceModifier) {
    val ctx = LocalContext.current
    val d = ctx.resources.displayMetrics.density
    val bm = MonoPainter.dashes(ctx, (width.value * d).toInt())
    Image(ImageProvider(bm), contentDescription = null, colorFilter = ColorFilter.tint(ColorProvider(RULE)), modifier = modifier.size(width, 1.dp))
}
