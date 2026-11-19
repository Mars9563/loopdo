package app.loopdo.desktop

import app.loopdo.desktop.notify.DbusNotifyApi
import app.loopdo.desktop.notify.NotifyApi
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live tests' notification server: with a desktop session the notifications are real (KDE shows them and gives
 * their ids), else a fake. Every call is recorded with the id it got.
 */
class RecordingNotifyApi : NotifyApi, AutoCloseable {
    data class Posted(val id: Long, val summary: String, val body: String, val actions: List<String>, val hints: Map<String, Variant<*>>)
    private val conn = runCatching { if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) null else DBusConnectionBuilder.forSessionBus().withShared(false).build() }.getOrNull()
    private val inner: NotifyApi = conn?.let(::DbusNotifyApi) ?: FakeNotifyApi()
    val real get() = conn != null
    val posted = CopyOnWriteArrayList<Posted>()
    val closed = CopyOnWriteArrayList<Long>()

    override fun notify(replaces: Long, summary: String, body: String, actions: List<String>, hints: Map<String, Variant<*>>, timeoutMs: Int): Long =
        inner.notify(replaces, summary, body, actions, hints, timeoutMs).also { posted += Posted(it, summary, body, actions, hints) }
    override fun close(id: Long) { closed += id; inner.close(id) }
    override fun server(): String? = inner.server()

    fun about(text: String) = posted.filter { it.summary.contains(text) || it.body.contains(text) }
    override fun close() { runCatching { conn?.close() } }
}
