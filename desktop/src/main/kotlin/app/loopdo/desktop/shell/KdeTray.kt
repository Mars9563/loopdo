@file:Suppress("FunctionName", "unused")

package app.loopdo.desktop.shell

import app.loopdo.client.log.logI
import app.loopdo.client.log.logW
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

// D6 (owner, 2026-11-19): Java's own tray (XEmbed through KDE's xembedsniproxy) shows an icon on Plasma 6 + Wayland
// but gets no clicks at all (tested: 0 of 3 reached Java). This is KDE's native tray instead: a StatusNotifierItem
// with a com.canonical.dbusmenu menu, on one D-Bus connection (dbus-java, plain Java). Same menu as the locked design.

/** One line of the tray menu: an action, or a separator. */
sealed interface TrayEntry {
    data class Action(val label: String, val onClick: () -> Unit) : TrayEntry
    data object Separator : TrayEntry
    /** A status line (shown, never clicked): "in use · phone quiet". */
    data class Info(val label: String) : TrayEntry
}

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)
    class NewToolTip(path: String) : DBusSignal(path)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

/** (iiay): one size of the icon, ARGB32 in network byte order. */
class Pixmap(@field:Position(0) @JvmField val width: Int, @field:Position(1) @JvmField val height: Int, @field:Position(2) @JvmField val argb: ByteArray) : Struct()

/** (sa(iiay)ss): icon name, icon, title, text. */
class ToolTip(@field:Position(0) @JvmField val icon: String, @field:Position(1) @JvmField val pixmaps: List<Pixmap>, @field:Position(2) @JvmField val title: String, @field:Position(3) @JvmField val text: String) : Struct()

/** (ia{sv}av): one menu node with its children (each a variant of the same struct). */
class MenuNode(@field:Position(0) @JvmField val id: Int, @field:Position(1) @JvmField val props: Map<String, Variant<*>>, @field:Position(2) @JvmField val children: List<Variant<*>>) : Struct()

/** (ia{sv}). */
class MenuItemProps(@field:Position(0) @JvmField val id: Int, @field:Position(1) @JvmField val props: Map<String, Variant<*>>) : Struct()

/** (isvu): one event of EventGroup. */
class MenuEvent(@field:Position(0) @JvmField val id: Int, @field:Position(1) @JvmField val eventId: String, @field:Position(2) @JvmField val data: Variant<*>, @field:Position(3) @JvmField val timestamp: UInt32) : Struct()

/** GetLayout's two out values (u revision, the layout); dbus-java wants a generic Tuple for several outs. */
class LayoutReply<A, B>(@field:Position(0) @JvmField val revision: A, @field:Position(1) @JvmField val layout: B) : Tuple()

/** AboutToShowGroup's two out values (ids to update, ids not found). */
class ShowGroupReply<A, B>(@field:Position(0) @JvmField val updatesNeeded: A, @field:Position(1) @JvmField val idErrors: B) : Tuple()

@DBusInterfaceName("com.canonical.dbusmenu")
interface DbusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): LayoutReply<UInt32, MenuNode>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProps>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): ShowGroupReply<List<Int>, List<Int>>
    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

/** The menu: item ids are 1…n in order (0 is the root); a new list bumps the revision so KDE asks again. */
class TrayMenu(private val onEvent: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) }) : DbusMenu, Properties {
    @Volatile private var entries: List<TrayEntry> = emptyList()
    @Volatile var revision = 1L
        private set

    /** Replaces the menu; true if it changed (the caller then sends LayoutUpdated). */
    @Synchronized fun set(new: List<TrayEntry>): Boolean {
        if (labels(new) == labels(entries)) { entries = new; return false } // same look: just the new callbacks
        entries = new; revision++
        return true
    }
    private fun labels(es: List<TrayEntry>) = es.map { when (it) { is TrayEntry.Action -> it.label; is TrayEntry.Info -> "i:" + it.label; TrayEntry.Separator -> "-" } }

    private fun propsOf(id: Int): Map<String, Variant<*>> =
        if (id == 0) mapOf("children-display" to Variant("submenu"))
        else when (val e = entries.getOrNull(id - 1)) {
            is TrayEntry.Action -> mapOf("label" to Variant(e.label), "enabled" to Variant(true), "visible" to Variant(true))
            TrayEntry.Separator -> mapOf("type" to Variant("separator"), "visible" to Variant(true))
            is TrayEntry.Info -> mapOf("label" to Variant(e.label), "enabled" to Variant(false), "visible" to Variant(true))
            null -> emptyMap()
        }

    private fun node(id: Int, depth: Int, names: List<String>): MenuNode {
        val props = propsOf(id).filterKeys { names.isEmpty() || it in names }
        val kids = if (id != 0 || depth == 0) emptyList() else entries.indices.map { i -> Variant(node(i + 1, depth - 1, names), "(ia{sv}av)") }
        return MenuNode(id, props, kids)
    }

    override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>) =
        LayoutReply(UInt32(revision), node(parentId, if (recursionDepth < 0) 1 else recursionDepth, propertyNames))
    override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>) =
        ids.map { MenuItemProps(it, propsOf(it).filterKeys { k -> propertyNames.isEmpty() || k in propertyNames }) }
    override fun GetProperty(id: Int, name: String): Variant<*> = propsOf(id)[name] ?: Variant("")
    override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
        if (eventId != "clicked") return
        val e = entries.getOrNull(id - 1) as? TrayEntry.Action ?: return
        onEvent(Runnable { runCatching { e.onClick() }.onFailure { logW("loopdo_tray", "a tray item failed", it) } })
    }
    override fun EventGroup(events: List<MenuEvent>): List<Int> { events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }; return emptyList() }
    override fun AboutToShow(id: Int) = false
    override fun AboutToShowGroup(ids: List<Int>) = ShowGroupReply(emptyList<Int>(), emptyList<Int>())

    private val own = mapOf<String, Variant<*>>(
        "Version" to Variant(UInt32(3)), "TextDirection" to Variant("ltr"), "Status" to Variant("normal"),
        "IconThemePath" to Variant(emptyList<String>(), "as"),
    )
    @Suppress("UNCHECKED_CAST") override fun <A : Any?> Get(iface: String, name: String): A = own[name] as A
    override fun <A : Any?> Set(iface: String, name: String, value: A) = Unit
    override fun GetAll(iface: String): Map<String, Variant<*>> = own
    override fun getObjectPath() = MENU_PATH
}

/** The item itself: the loopdo_ icon, a tooltip, left click opens the app; the menu is KDE's from [TrayMenu]. */
class TrayItem(icon: BufferedImage?, private val tooltip: String, private val onActivate: () -> Unit) : StatusNotifierItem, Properties {
    private val pixmaps = icon?.let { img -> listOf(22, 32, 48, 64).map { pixmapOf(img, it) } }.orEmpty()

    private fun props(): Map<String, Variant<*>> = mapOf(
        "Category" to Variant("ApplicationStatus"), "Id" to Variant("loopdo"), "Title" to Variant(tooltip),
        "Status" to Variant("Active"), "WindowId" to Variant(0),
        "IconName" to Variant(if (pixmaps.isEmpty()) "checkbox" else ""), "IconPixmap" to Variant(pixmaps, "a(iiay)"),
        "OverlayIconName" to Variant(""), "OverlayIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
        "AttentionIconName" to Variant(""), "AttentionIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"), "AttentionMovieName" to Variant(""),
        "ToolTip" to Variant(ToolTip("", emptyList(), tooltip, ""), "(sa(iiay)ss)"),
        "ItemIsMenu" to Variant(false), "Menu" to Variant(DBusPath(MENU_PATH), "o"),
    )

    override fun Activate(x: Int, y: Int) = SwingUtilities.invokeLater { runCatching(onActivate).onFailure { logW("loopdo_tray", "open failed", it) } }
    override fun SecondaryActivate(x: Int, y: Int) = Unit
    override fun ContextMenu(x: Int, y: Int) = Unit // KDE shows the dbusmenu itself
    override fun Scroll(delta: Int, orientation: String) = Unit
    @Suppress("UNCHECKED_CAST") override fun <A : Any?> Get(iface: String, name: String): A = props()[name] as A
    override fun <A : Any?> Set(iface: String, name: String, value: A) = Unit
    override fun GetAll(iface: String): Map<String, Variant<*>> = if (iface == SNI) props() else emptyMap()
    override fun getObjectPath() = ITEM_PATH
}

/** ARGB32, big-endian (the StatusNotifierItem spec), scaled to [size]×[size]. */
fun pixmapOf(src: BufferedImage, size: Int): Pixmap {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    img.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        drawImage(src, 0, 0, size, size, null); dispose()
    }
    val out = ByteArray(size * size * 4)
    var i = 0
    for (y in 0 until size) for (x in 0 until size) {
        val p = img.getRGB(x, y)
        out[i++] = (p ushr 24).toByte(); out[i++] = (p ushr 16).toByte(); out[i++] = (p ushr 8).toByte(); out[i++] = p.toByte()
    }
    return Pixmap(size, size, out)
}

/**
 * The tray on the session bus. [start] answers null when there is no KDE-style tray (no StatusNotifierWatcher, no
 * session bus): the caller then falls back to Compose's own Tray. [register] false exports without showing (tests).
 */
class KdeTray private constructor(private val conn: DBusConnection, val busName: String, val menu: TrayMenu, private val register: Boolean) : AutoCloseable {
    fun setMenu(entries: List<TrayEntry>) {
        if (menu.set(entries)) runCatching { conn.sendMessage(DbusMenu.LayoutUpdated(MENU_PATH, UInt32(menu.revision), 0)) }
            .onFailure { logW("loopdo_tray", "menu update failed", it) }
    }

    private fun registerWithWatcher() {
        if (!register) return
        conn.getRemoteObject(WATCHER, "/StatusNotifierWatcher", StatusNotifierWatcher::class.java).RegisterStatusNotifierItem(busName)
        logI("loopdo_tray", "tray registered")
    }

    override fun close() { runCatching { conn.close() } }

    companion object {
        fun start(icon: BufferedImage?, tooltip: String, onActivate: () -> Unit, register: Boolean = true, menu: TrayMenu = TrayMenu()): KdeTray? = runCatching {
            if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return null
            val conn = DBusConnectionBuilder.forSessionBus().withShared(false).build()
            val dbus = conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            if (register && !dbus.NameHasOwner(WATCHER)) { conn.close(); return null } // not a KDE-style tray: Compose's own
            val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
            conn.requestBusName(name)
            conn.exportObject(ITEM_PATH, TrayItem(icon, tooltip, onActivate))
            conn.exportObject(MENU_PATH, menu)
            val tray = KdeTray(conn, name, menu, register)
            tray.registerWithWatcher()
            // Plasma restarted (a new watcher): register again, or the icon is gone until loopdo_ restarts.
            conn.addSigHandler(DBus.NameOwnerChanged::class.java) { s ->
                if (s.name == WATCHER && s.newOwner.isNotEmpty()) runCatching { tray.registerWithWatcher() }.onFailure { logW("loopdo_tray", "re-register failed", it) }
            }
            tray
        }.getOrElse { logW("loopdo_tray", "no KDE tray, using the Java one", it); null }

        /** The packaged tray.png (the same picture the Compose tray uses). */
        fun loadIcon(): BufferedImage? = runCatching { KdeTray::class.java.getResourceAsStream("/tray.png")?.use { ImageIO.read(it) } }.getOrNull()
    }
}

const val SNI = "org.kde.StatusNotifierItem"
private const val WATCHER = "org.kde.StatusNotifierWatcher"
const val ITEM_PATH = "/StatusNotifierItem"
const val MENU_PATH = "/MenuBar"
