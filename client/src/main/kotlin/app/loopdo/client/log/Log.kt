package app.loopdo.client.log

/** Where shared client code logs (D2). The phone points it at android.util.Log; the laptop at stderr/a file. */
enum class Level { DEBUG, INFO, WARN, ERROR }

fun interface LogSink {
    fun log(level: Level, tag: String, msg: String, e: Throwable?)
}

object Logs {
    @Volatile
    var sink: LogSink = LogSink { level, tag, msg, e ->
        System.err.println("$level/$tag: $msg")
        e?.printStackTrace()
    }
}

fun logD(tag: String, msg: String) = Logs.sink.log(Level.DEBUG, tag, msg, null)
fun logI(tag: String, msg: String) = Logs.sink.log(Level.INFO, tag, msg, null)
fun logW(tag: String, msg: String, e: Throwable? = null) = Logs.sink.log(Level.WARN, tag, msg, e)
fun logE(tag: String, msg: String, e: Throwable? = null) = Logs.sink.log(Level.ERROR, tag, msg, e)
