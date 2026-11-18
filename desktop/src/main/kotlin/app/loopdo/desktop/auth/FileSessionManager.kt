package app.loopdo.desktop.auth

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The laptop keeps its Supabase session in the data folder (mode 600), so deleting the folder signs out and the
 * test runs never share the real session. Atomic writes; an unreadable file means signed out.
 */
class FileSessionManager(private val file: File) : SessionManager {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun saveSession(session: UserSession) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(UserSession.serializer(), session))
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Throws when there is no readable session; supabase-kt's loadSessionOrNull() turns that into "signed out". */
    override suspend fun loadSession(): UserSession {
        if (!file.exists()) throw NoSuchElementException("not signed in on this laptop yet")
        return json.decodeFromString(UserSession.serializer(), file.readText())
    }

    override suspend fun deleteSession() { file.delete() }
}
