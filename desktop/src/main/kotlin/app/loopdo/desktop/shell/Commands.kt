package app.loopdo.desktop.shell

/** What a launch asks for: `loopdo`, `--quick` (Meta+J), `--background` (login), `--demo`, `--self-check <png>`. */
sealed interface Command {
    data object Open : Command
    data object Quick : Command
    data object Background : Command
    data object Demo : Command
    data class SelfCheck(val out: String) : Command

    /** One word on the single-instance socket. */
    fun wire(): String = when (this) { Open -> "open"; Quick -> "quick"; Background -> "background"; Demo -> "demo"; is SelfCheck -> "open" }

    companion object {
        fun fromWire(s: String): Command = when (s.trim()) { "quick" -> Quick; "background" -> Background; "demo" -> Demo; else -> Open }
    }
}

fun parseCommand(args: Array<String>): Command = when {
    "--self-check" in args -> Command.SelfCheck(args.getOrNull(args.indexOf("--self-check") + 1) ?: "self-check.png")
    "--quick" in args -> Command.Quick
    "--background" in args -> Command.Background
    "--demo" in args -> Command.Demo
    else -> Command.Open
}

/** What a command does to the windows: quick add opens alone (the main window stays as it is); open shows the app. */
fun route(c: Command, shown: androidx.compose.runtime.MutableState<Boolean>, quick: androidx.compose.runtime.MutableState<Boolean>) {
    when (c) {
        Command.Quick -> quick.value = true
        Command.Background -> Unit
        else -> shown.value = true
    }
}
