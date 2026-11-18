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
import app.loopdo.desktop.ui.SignInScreen
import app.loopdo.desktop.ui.CenterLine
import app.loopdo.desktop.ui.BlockedScreen
import kotlinx.coroutines.launch
import app.loopdo.desktop.ui.Fern
import app.loopdo.client.auth.AuthState
import app.loopdo.client.onboarding.hasDaySettings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import java.io.File
import androidx.compose.ui.window.Tray
import app.loopdo.desktop.shell.Command
import app.loopdo.desktop.shell.SingleInstance
import app.loopdo.desktop.shell.parseCommand
import app.loopdo.desktop.shell.socketFor
import app.loopdo.desktop.shell.lockFor

fun main(args: Array<String>) {
    val cmd = parseCommand(args)
    if (cmd is Command.SelfCheck) return selfCheck(File(cmd.out))
    val demo = cmd == Command.Demo
    val dir = if (demo) java.nio.file.Files.createTempDirectory("loopdo-demo").toFile() else dataDir()
    val single = SingleInstance(socketFor(dir), lockFor(dir))
    val shown = mutableStateOf(cmd != Command.Background && cmd != Command.Quick)
    val quick = mutableStateOf(cmd == Command.Quick)
    if (!single.tryServe { c -> when (c) { Command.Quick -> quick.value = true; Command.Background -> Unit; else -> shown.value = true } }) {
        single.send(cmd) // the running loopdo_ shows itself (or quick add, from D3)
        return
    }
    application {
        val graph = remember { if (demo) null else DesktopGraph(dir) }
        // Quick add: its own small window over whatever is open; the main window stays as it is (hidden or not).
        if (quick.value && graph != null) {
            val signedIn = graph.auth.session.collectAsState().value is AuthState.SignedIn
            if (!signedIn) { shown.value = true; quick.value = false }
            else {
                val qs = androidx.compose.ui.window.rememberWindowState(
                    width = 600.dp, height = 150.dp,
                    position = androidx.compose.ui.window.WindowPosition.Aligned(androidx.compose.ui.Alignment.TopCenter),
                )
                Window(onCloseRequest = { quick.value = false }, state = qs, title = "loopdo_ quick add", undecorated = true, transparent = true,
                    alwaysOnTop = true, resizable = false, icon = androidx.compose.ui.res.painterResource("tray.png")) {
                    LaunchedEffect(Unit) { window.toFront(); window.requestFocus() }
                    val vm = remember { graph.popupViewModel() }
                    LoopDoTheme { app.loopdo.desktop.ui.QuickAdd(graph, vm) { quick.value = false } }
                }
            }
        }
        val quit = {
            // The database closes first; only then is the lock released for a next launch (D2 review).
            graph?.sync?.db?.let { db -> kotlinx.coroutines.runBlocking { runCatching { db.close() } } }
            single.close(); exitApplication()
        }
        Tray(
            icon = androidx.compose.ui.res.painterResource("tray.png"),
            tooltip = "loopdo_",
            onAction = { shown.value = true },
            menu = {
                Item("[open]", onClick = { shown.value = true })
                if (graph != null) Item("[quick add]", onClick = { quick.value = true })
                Separator()
                Item("[quit]", onClick = quit)
            },
        )
        // Closing the window keeps loopdo_ in the tray (pings and quick add need it running); [quit] really quits.
        Window(
            onCloseRequest = { shown.value = false }, visible = shown.value, title = "loopdo_",
            icon = androidx.compose.ui.res.painterResource("tray.png"),
            state = rememberWindowState(width = 1200.dp, height = 740.dp),
        ) {
            window.minimumSize = java.awt.Dimension(1000, 640)
            LoopDoTheme {
                if (graph == null) DemoApp() else Root(graph) { app.loopdo.desktop.ui.MainApp(graph) }
            }
        }
    }
}

/** `--demo`: the phone's sample day, nothing signed in, no network (for looks and screenshots). */
@Composable
fun DemoApp() {
    var place by remember { mutableStateOf(Place.TODAY) }
    Shell(place, { place = it }, savedStatus("all saved · just now")) {
        if (place == Place.TODAY) app.loopdo.desktop.ui.TodayPane(app.loopdo.client.today.demoToday(), app.loopdo.client.today.DemoUi, null,
            "focus › gym 4× · › pay rent", onWrite = {}, onAdd = {})
        else BasicText("${place.path} (demo)", style = LocalType.current.small)
    }
}

@Composable
fun App() {
    var place by remember { mutableStateOf(Place.TODAY) }
    Shell(place, { place = it }, savedStatus("all saved · just now")) {
        BasicText("${place.path} — coming in a later step", style = LocalType.current.small)
    }
}

/** Start-up states: sign in → (another account's changes waiting) → restoring your data… → setup on the phone → the app. */
@Composable
fun Root(g: DesktopGraph, signedInApp: @Composable () -> Unit) {
    val session by g.auth.session.collectAsState()
    val blocked by g.sync.blocked.collectAsState()
    val status by remember { g.sync.db.currentStatus.asFlow() }.collectAsState(g.sync.db.currentStatus)
    val hasSettings by remember { g.sync.db.hasDaySettings() }.collectAsState(false)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    when {
        session is AuthState.Loading -> CenterLine("loopdo_")
        session is AuthState.SignedOut -> SignInScreen { e, p -> g.auth.signIn(e, p) }
        blocked != null -> BlockedScreen(blocked!!) { scope.launch { g.auth.signOut() } }
        status.hasSynced != true && !hasSettings -> CenterLine("restoring your data…")
        !hasSettings -> CenterLine("finish setting up loopdo_ on your phone first (day end + time zone).")
        else -> signedInApp()
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
