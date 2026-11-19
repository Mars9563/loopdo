package app.loopdo.desktop.auth

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The laptop keeps its Supabase session in the data folder (mode 600), so deleting the folder signs out and the
 * test runs never share the real session. Atomic writes; an unreadable file means signed out.
 */
class FileSessionManager(private val file: File) : SessionManager {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun saveSession(session: UserSession) {
        // The tokens are never readable by other users, not even for a moment: the folder is owner-only and the file is
        // created rw------- before anything is written (it was chmod-ed after the write; ultrareview).
        file.parentFile?.let { d -> d.mkdirs(); runCatching { Files.setPosixFilePermissions(d.toPath(), PosixFilePermissions.fromString("rwx------")) } }
        val tmp = File(file.path + ".tmp")
        tmp.delete()
        runCatching { Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
            .onFailure { tmp.createNewFile() } // a filesystem without POSIX permissions
        tmp.writeText(json.encodeToString(UserSession.serializer(), session))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Throws when there is no readable session; supabase-kt's loadSessionOrNull() turns that into "signed out". */
    override suspend fun loadSession(): UserSession {
        if (!file.exists()) throw NoSuchElementException("not signed in on this laptop yet")
        return json.decodeFromString(UserSession.serializer(), file.readText())
    }

    override suspend fun deleteSession() { file.delete() }
}
