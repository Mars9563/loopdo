package app.loopdo.desktop

import java.io.File

/** The laptop's data folder: database, session, anchor, drafts, photo cache. `LOOPDO_DATA_DIR` overrides (tests, demo). */
fun dataDir(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): File =
    env["LOOPDO_DATA_DIR"]?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "loopdo")
