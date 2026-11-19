package app.loopdo.desktop.ui

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.loopdo.desktop.store.FileKeyValue
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.math.roundToInt

/**
 * The app's zoom (owner, 2026-11-19): at KDE's 100% a 2560×1440 laptop panel (~190 dpi) draws loopdo_ at half the
 * size it was designed for. Every window scales by [value] (text, spacing, window sizes); the default comes from the
 * screen, Ctrl + = / - / 0 change it like a browser, and the choice is kept in dataDir/ui.json.
 */
class UiZoom(private val kv: FileKeyValue, private val auto: Float = autoZoom()) {
    private val state = mutableFloatStateOf(kv.getString(KEY)?.toFloatOrNull()?.coerceIn(MIN, MAX) ?: auto)
    val value: Float get() = state.floatValue

    fun set(z: Float) {
        val v = ((z * 20).roundToInt() / 20f).coerceIn(MIN, MAX) // 5% steps
        state.floatValue = v
        // A full or read-only disk only loses the remembered zoom, never the key press (ultrareview).
        runCatching { if (v == auto) kv.remove(KEY) else kv.putString(KEY, v.toString()) }
    }

    /** Ctrl + = (or +) bigger, Ctrl + - smaller, Ctrl + 0 back to the screen's default. True if it was one. */
    fun handle(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return false
        when (e.key) {
            Key.Equals, Key.Plus, Key.NumPadAdd -> set(value + STEP)
            Key.Minus, Key.NumPadSubtract -> set(value - STEP)
            Key.Zero, Key.NumPad0 -> set(auto)
            else -> return false
        }
        return true
    }

    companion object {
        const val MIN = 0.75f
        const val MAX = 3f
        const val STEP = 0.1f
        private const val KEY = "zoom"
        fun at(dataDir: File) = UiZoom(FileKeyValue(File(dataDir, "ui.json")))
    }
}

/**
 * The default for this screen: its height in logical pixels (after KDE's own scaling, which Java already applies)
 * → 1440 and up 140% (the owner's sweet spot), 2000 and up 200%, else 100%.
 */
fun autoZoom(): Float = runCatching {
    if (GraphicsEnvironment.isHeadless()) return 1f
    val dev = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
    val scale = dev.defaultConfiguration.defaultTransform.scaleY.takeIf { it > 0 } ?: 1.0
    zoomFor(dev.displayMode.height / scale)
}.getOrDefault(1f)

fun zoomFor(logicalHeight: Double): Float = when {
    logicalHeight >= 2000 -> 2f
    logicalHeight >= 1400 -> 1.4f // the owner's sweet spot on 1440p (2026-11-19)
    else -> 1f
}
