package app.loopdo.desktop

import app.loopdo.desktop.shell.Command
import app.loopdo.desktop.shell.SingleInstance
import app.loopdo.desktop.shell.parseCommand
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellTest {
    @Test
    fun `each flag maps to its command`() {
        assertEquals(Command.Open, parseCommand(emptyArray()))
        assertEquals(Command.Quick, parseCommand(arrayOf("--quick")))
        assertEquals(Command.Background, parseCommand(arrayOf("--background")))
        assertEquals(Command.Demo, parseCommand(arrayOf("--demo")))
        assertEquals(Command.SelfCheck("x.png"), parseCommand(arrayOf("--self-check", "x.png")))
        assertEquals(Command.Open, parseCommand(arrayOf("--nonsense")))
    }

    @Test
    fun `a second launch hands its command to the running app and stops`() {
        val sock = File(Files.createTempDirectory("loopdo-sock").toFile(), "loopdo.sock")
        val got = LinkedBlockingQueue<Command>()
        val first = SingleInstance(sock)
        assertTrue(first.tryServe { got.put(it) }, "the first one serves")
        val second = SingleInstance(sock)
        assertFalse(second.tryServe { error("must not serve") }, "the second one hands over")
        second.send(Command.Quick)
        assertEquals(Command.Quick, got.poll(5, TimeUnit.SECONDS))
        first.close()
        assertTrue(SingleInstance(sock).tryServe { }, "after the first quits, a new one can serve (stale socket file removed)")
    }
}
