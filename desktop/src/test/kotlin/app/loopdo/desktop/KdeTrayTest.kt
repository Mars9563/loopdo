package app.loopdo.desktop

import app.loopdo.desktop.notify.runCommand
import app.loopdo.desktop.shell.KdeTray
import app.loopdo.desktop.shell.TrayEntry
import app.loopdo.desktop.shell.TrayMenu
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The KDE tray on the real session bus, exported but not registered (nothing shows), called as Plasma calls it. */
class KdeTrayTest {
    private fun gdbus(vararg a: String) = runCommand(listOf("gdbus", "call", "--session") + a)

    @Test
    fun `plasma reads the icon and the menu, and a click runs the item`() {
        assumeTrue("needs a session bus + gdbus", System.getenv("DBUS_SESSION_BUS_ADDRESS") != null && File("/usr/bin/gdbus").exists())
        val clicked = mutableListOf<String>()
        val opened = CountDownLatch(1)
        val tray = KdeTray.start(KdeTray.loadIcon(), "loopdo_", onActivate = { opened.countDown() }, register = false, menu = TrayMenu(onEvent = { it.run() }))
        assertTrue(tray != null, "exported on the session bus")
        tray.use { t ->
            t.setMenu(listOf(TrayEntry.Action("[open]") { clicked += "open" }, TrayEntry.Action("[quick add]") { clicked += "quick" }, TrayEntry.Separator, TrayEntry.Action("[quit]") { clicked += "quit" }))
            val dest = arrayOf("--dest", t.busName)

            val all = gdbus(*dest, "--object-path", "/StatusNotifierItem", "--method", "org.freedesktop.DBus.Properties.GetAll", "org.kde.StatusNotifierItem")
            assertTrue(all.contains("'Id': <'loopdo'>") && all.contains("'Menu': <objectpath '/MenuBar'>") && all.contains("'ItemIsMenu': <false>"), all)
            assertTrue(Regex("""'IconPixmap': <\[\(22, 22, """).containsMatchIn(all), "the icon is sent as pixmaps")

            val layout = gdbus(*dest, "--object-path", "/MenuBar", "--method", "com.canonical.dbusmenu.GetLayout", "--", "0", "-1", "[]")
            assertTrue(layout.contains("'[open]'") && layout.contains("'[quick add]'") && layout.contains("'separator'") && layout.contains("'[quit]'"), layout)
            assertTrue(layout.indexOf("[open]") < layout.indexOf("[quick add]") && layout.indexOf("separator") < layout.indexOf("[quit]"), "in order")

            gdbus(*dest, "--object-path", "/MenuBar", "--method", "com.canonical.dbusmenu.Event", "2", "clicked", "<0>", "0")
            assertEquals(listOf("quick"), clicked)
            gdbus(*dest, "--object-path", "/MenuBar", "--method", "com.canonical.dbusmenu.Event", "3", "clicked", "<0>", "0") // the separator
            assertEquals(listOf("quick"), clicked, "a separator does nothing")

            gdbus(*dest, "--object-path", "/StatusNotifierItem", "--method", "org.kde.StatusNotifierItem.Activate", "0", "0")
            assertTrue(opened.await(5, TimeUnit.SECONDS), "left click opens the app")

            // A new label (the pings switch) bumps the revision, so KDE asks again.
            val before = Regex("""^\(uint32 (\d+)""").find(layout)!!.groupValues[1].toInt()
            t.setMenu(listOf(TrayEntry.Action("[open]") {}, TrayEntry.Action("[pings on this laptop: off]") {}))
            val after = gdbus(*dest, "--object-path", "/MenuBar", "--method", "com.canonical.dbusmenu.GetLayout", "--", "0", "-1", "[]")
            assertTrue(Regex("""^\(uint32 (\d+)""").find(after)!!.groupValues[1].toInt() > before && after.contains("[pings on this laptop: off]"), after)
        }
    }

    @Test
    fun `a status line is shown but can't be clicked`() {
        var clicked = false
        val m = TrayMenu(onEvent = { it.run() })
        m.set(listOf(TrayEntry.Action("[open]") { clicked = true }, TrayEntry.Info("in use · phone quiet")))
        val node = m.GetLayout(0, -1, emptyList()).layout
        val info = node.children[1].value as app.loopdo.desktop.shell.MenuNode
        assertEquals("in use · phone quiet", info.props["label"]?.value)
        assertEquals(false, info.props["enabled"]?.value)
        m.Event(2, "clicked", org.freedesktop.dbus.types.Variant(0), org.freedesktop.dbus.types.UInt32(0))
        assertTrue(!clicked)
    }
}
