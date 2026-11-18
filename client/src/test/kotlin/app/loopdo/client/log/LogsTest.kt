package app.loopdo.client.log

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LogsTest {
    private val before = Logs.sink

    @AfterTest fun restore() { Logs.sink = before }

    @Test
    fun `a set sink receives each call with its level, tag and message`() {
        val got = mutableListOf<List<Any?>>()
        Logs.sink = LogSink { level, tag, msg, e -> got += listOf(level, tag, msg, e) }
        val boom = IllegalStateException("x")
        logI("t", "info")
        logW("t", "m")
        logE("t", "bad", boom)
        assertEquals<List<List<Any?>>>(listOf(listOf(Level.INFO, "t", "info", null), listOf(Level.WARN, "t", "m", null), listOf(Level.ERROR, "t", "bad", boom)), got)
    }
}
