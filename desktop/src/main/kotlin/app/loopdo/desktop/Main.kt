package app.loopdo.desktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.loopdo.desktop.ui.LocalType
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.Place
import app.loopdo.desktop.ui.Shell
import app.loopdo.desktop.ui.savedStatus
import java.io.File

fun main(args: Array<String>) {
    if ("--self-check" in args) return selfCheck(File(args.getOrNull(args.indexOf("--self-check") + 1) ?: "self-check.png"))
    application {
        Window(onCloseRequest = ::exitApplication, title = "loopdo_", state = rememberWindowState(width = 1200.dp, height = 740.dp)) {
            window.minimumSize = java.awt.Dimension(1000, 640)
            LoopDoTheme { App() }
        }
    }
}

@Composable
fun App() {
    var place by remember { mutableStateOf(Place.TODAY) }
    Shell(place, { place = it }, savedStatus("all saved · just now")) {
        BasicText("${place.path} — coming in a later step", style = LocalType.current.small)
    }
}

/** Renders the window off-screen to a PNG (no display needed), for checking against the locked mockups. */
fun selfCheck(out: File, content: @Composable () -> Unit = { App() }) {
    val scene = ImageComposeScene(1200, 740, Density(1f)) { LoopDoTheme { content() } }
    val img = scene.render()
    out.parentFile?.mkdirs()
    out.writeBytes(img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("self-check: wrote ${out.absolutePath}")
}
