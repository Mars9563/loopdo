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
class SingleInstance(private val socket: File, private val lockFile: File = File(socket.path + ".lock")) {
    private var server: ServerSocketChannel? = null
    private var lockChannel: java.nio.channels.FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    /**
     * True if this process now serves; false if another one is running (then use [send]). Who serves is decided by an
     * exclusive lock on [lockFile], held for the life of the process (the OS drops it if the process dies), so two
     * launches at the same moment can never both open the database (D2 review); the socket only carries commands.
     */
    @Synchronized
    fun tryServe(onCommand: (Command) -> Unit): Boolean {
        lockFile.parentFile?.mkdirs()
        val lc = java.nio.channels.FileChannel.open(lockFile.toPath(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
        val l = try { lc.tryLock() } catch (e: java.nio.channels.OverlappingFileLockException) { null }
        if (l == null) { lc.close(); return false }
        lockChannel = lc; lock = l
        socket.delete() // stale file from a crash (we hold the lock, so nobody else is serving)
        socket.parentFile?.mkdirs()
        val ch = try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket.toPath())) }
        } catch (e: Exception) {
            // No socket (a path past the ~108-byte limit, a filesystem without sockets): we still hold the lock, so
            // run without it; returning false here looked like "another copy is running" and nothing ever opened.
            logW("loopdo_shell", "no command socket at $socket; later launches can't hand over their command", e)
            return true
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

    /** Hands [cmd] to the running loopdo_ (retries ~2 s while it is still starting up). */
    fun send(cmd: Command) {
        repeat(20) { i ->
            val ok = runCatching { SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath())).use { it.write(ByteBuffer.wrap((cmd.wire() + "\n").toByteArray())) } }.isSuccess
            if (ok) return
            if (i < 19) Thread.sleep(100)
        }
    }

    /** Stops serving; call only after the database is closed (the lock is what keeps a second launch out). */
    @Synchronized
    fun close() {
        server?.close(); server = null
        if (lock != null) socket.delete()
        runCatching { lock?.release() }; lock = null
        runCatching { lockChannel?.close() }; lockChannel = null
    }

    private fun readLine(c: SocketChannel): String {
        val buf = ByteBuffer.allocate(64)
        val out = StringBuilder()
        while (c.read(buf) > 0) { buf.flip(); out.append(StandardCharsets.UTF_8.decode(buf)); buf.clear(); if ('\n' in out) break }
        return out.toString().substringBefore('\n')
    }
}

/** The socket lives in the data folder itself: one per folder, and every launch finds it whatever its environment. */
fun socketFor(dataDir: File): File = File(dataDir, "loopdo.sock")
fun lockFor(dataDir: File): File = File(dataDir, "loopdo.lock")
