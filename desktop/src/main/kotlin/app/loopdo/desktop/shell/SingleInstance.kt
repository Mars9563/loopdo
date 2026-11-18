package app.loopdo.desktop.shell

import app.loopdo.client.log.logW
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

/**
 * One running loopdo_ per data folder: the first launch listens on a Unix socket; a later launch (the menu entry,
 * Meta+J) sends its command there and exits, so two processes never open the same database.
 */
class SingleInstance(private val socket: File) {
    private var server: ServerSocketChannel? = null

    /** True if this process now serves; false if another one is running (then use [send]). */
    fun tryServe(onCommand: (Command) -> Unit): Boolean {
        if (reachable()) return false
        socket.delete() // stale file from a crash
        socket.parentFile?.mkdirs()
        val ch = try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket.toPath())) }
        } catch (e: java.io.IOException) {
            return false // another launch bound it a moment ago
        }
        server = ch
        Thread({
            while (ch.isOpen) {
                val c = try { ch.accept() } catch (e: Exception) { break }
                runCatching { c.use { val w = readLine(it); if (w != "ping") onCommand(Command.fromWire(w)) } }.onFailure { logW("loopdo_shell", "bad command", it) }
            }
        }, "loopdo-single-instance").apply { isDaemon = true }.start()
        return true
    }

    fun send(cmd: Command) {
        SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath())).use { it.write(ByteBuffer.wrap((cmd.wire() + "\n").toByteArray())) }
    }

    fun close() {
        server?.close(); server = null
        socket.delete()
    }

    private fun reachable(): Boolean = socket.exists() && runCatching {
        SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath())).use { it.write(ByteBuffer.wrap("ping\n".toByteArray())) }
    }.isSuccess

    private fun readLine(c: SocketChannel): String {
        val buf = ByteBuffer.allocate(64)
        val out = StringBuilder()
        while (c.read(buf) > 0) { buf.flip(); out.append(StandardCharsets.UTF_8.decode(buf)); buf.clear(); if ('\n' in out) break }
        return out.toString().substringBefore('\n')
    }
}

/** `$XDG_RUNTIME_DIR/loopdo-<folder hash>.sock`: one socket per data folder (the demo and tests never meet the real app). */
fun socketFor(dataDir: File, env: Map<String, String> = System.getenv()): File =
    File(env["XDG_RUNTIME_DIR"] ?: System.getProperty("java.io.tmpdir"), "loopdo-${Integer.toHexString(dataDir.absolutePath.hashCode())}.sock")
