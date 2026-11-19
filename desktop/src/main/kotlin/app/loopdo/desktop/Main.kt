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
import androidx.compose.runtime.SideEffect
import app.loopdo.desktop.shell.TrayEntry
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
    if (!single.tryServe { c -> app.loopdo.desktop.shell.route(c, shown, quick) }) {
        single.send(cmd) // the running loopdo_ shows itself (or quick add, from D3)
        return
    }
    val zoom = app.loopdo.desktop.ui.UiZoom.at(dir)
    application {
        val graph = remember { if (demo) null else DesktopGraph(dir) }
        val z = zoom.value
        // D6: the laptop rings the phone's reminders; a reminder's [write] opens quick add, [review] shows the app.
        val pings = remember { graph?.let { app.loopdo.desktop.notify.DesktopPings(it, onWrite = { quick.value = true }, onOpen = { shown.value = true }).also { p -> p.start() } } }
        var pingsOn by remember { mutableStateOf(pings?.enabled ?: false) }
        // Quick add: its own small window over whatever is open; the main window stays as it is (hidden or not).
        if (quick.value && graph != null) {
            val signedIn = graph.auth.session.collectAsState().value is AuthState.SignedIn
            val blocked = graph.sync.blocked.collectAsState().value
            val hasSettings = remember { graph.sync.db.hasDaySettings() }.collectAsState(null).value
            if (hasSettings == null && signedIn && blocked == null) Unit // still reading the database
            else if (!app.loopdo.desktop.ui.quickAllowed(signedIn, blocked, hasSettings == true)) { shown.value = true; quick.value = false }
            else {
                val qs = androidx.compose.ui.window.rememberWindowState(
                    width = 600.dp * z, height = 150.dp * z,
                    position = androidx.compose.ui.window.WindowPosition.Aligned(androidx.compose.ui.Alignment.TopCenter),
                )
                Window(onCloseRequest = { quick.value = false }, state = qs, title = "loopdo_ quick add", undecorated = true, transparent = true, onPreviewKeyEvent = zoom::handle,
                    alwaysOnTop = true, resizable = false, icon = androidx.compose.ui.res.painterResource("tray.png")) {
                    LaunchedEffect(Unit) { window.toFront(); window.requestFocus() }
                    val vm = remember { graph.popupViewModel() }
                    LaunchedEffect(z) { qs.size = androidx.compose.ui.unit.DpSize(600.dp * z, 150.dp * z) }
                    LoopDoTheme(z) { app.loopdo.desktop.ui.QuickAdd(graph, vm) { quick.value = false } }
                }
            }
        }
        // KDE's own tray (Java's gets no clicks on Plasma 6 + Wayland); Compose's Tray only where there is none.
        val kdeTray = remember { app.loopdo.desktop.shell.KdeTray.start(app.loopdo.desktop.shell.KdeTray.loadIcon(), "loopdo_", onActivate = { shown.value = true }) }
        val quit = {
            // D7: the phone rings again from now; then the database closes, and only then is the lock released (D2 review).
            pings?.let { p -> kotlinx.coroutines.runBlocking { runCatching { kotlinx.coroutines.withTimeout(3_000) { p.stopPresence() } } } }
            graph?.sync?.db?.let { db -> kotlinx.coroutines.runBlocking { runCatching { db.close() } } }
            kdeTray?.close()
            single.close(); exitApplication()
        }
        // The locked tray menu, once for both trays.
        val entries = buildList {
            add(TrayEntry.Action("[open]") { shown.value = true })
            if (graph != null) add(TrayEntry.Action("[quick add]") { quick.value = true })
            if (pings != null) add(TrayEntry.Action(if (pingsOn) "[pings on this laptop: on]" else "[pings on this laptop: off]") { pingsOn = !pingsOn; pings.enabled = pingsOn })
            add(TrayEntry.Separator)
            add(TrayEntry.Action("[quit]", quit))
        }
        if (kdeTray != null) SideEffect { kdeTray.setMenu(entries) }
        else Tray(
            icon = androidx.compose.ui.res.painterResource("tray.png"),
            tooltip = "loopdo_",
            onAction = { shown.value = true },
            menu = {
                entries.forEach { e ->
                    when (e) {
                        is TrayEntry.Action -> Item(e.label, onClick = e.onClick)
                        TrayEntry.Separator -> Separator()
                    }
                }
            },
        )
        val main = rememberWindowState(width = 1200.dp * z, height = 740.dp * z)
        // Closing the window keeps loopdo_ in the tray (pings and quick add need it running); [quit] really quits.
        Window(
            onCloseRequest = { shown.value = false }, visible = shown.value, title = "loopdo_",
            icon = androidx.compose.ui.res.painterResource("tray.png"),
            state = main,
            onPreviewKeyEvent = zoom::handle,
        ) {
            // The zoom grows the window with its content (the same layout, bigger), and its smallest size with it.
            var lastZ by remember { mutableStateOf(z) }
            LaunchedEffect(z) {
                window.minimumSize = java.awt.Dimension((1000 * z).toInt(), (640 * z).toInt())
                if (z != lastZ) { main.size = androidx.compose.ui.unit.DpSize(main.size.width * (z / lastZ), main.size.height * (z / lastZ)); lastZ = z }
            }
            LoopDoTheme(z) {
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
