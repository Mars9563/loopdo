package app.loopdo.desktop.shell

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * "start at login" (locked ~/settings): the same entry `install-local.sh --autostart` writes, in
 * ~/.config/autostart. Written to a temp file and moved into place, so a crash never leaves half an entry.
 */
class Autostart(
    private val dir: File = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config", "autostart"),
    private val bin: String = "${System.getProperty("user.home")}/.local/bin/loopdo",
) {
    private val file get() = File(dir, "loopdo.desktop")
    val on: Boolean get() = file.isFile

    fun set(on: Boolean) {
        if (!on) { file.delete(); return }
        dir.mkdirs()
        val tmp = File(dir, ".loopdo.desktop.tmp")
        tmp.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=loopdo_
            Exec=$bin --background
            Icon=loopdo
            Terminal=false
            X-GNOME-Autostart-enabled=true
            """.trimIndent() + "\n",
        )
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
