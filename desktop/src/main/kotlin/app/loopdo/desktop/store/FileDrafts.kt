package app.loopdo.desktop.store

import app.loopdo.client.log.logW
import app.loopdo.client.popup.PopupDrafts
import java.io.File

/** Unsaved journal text (the phone's PopupDrafts), in the data folder so closing the window never loses it. */
class FilePopupDrafts(file: File) : PopupDrafts {
    private val kv = FileKeyValue(file)
    private val dir = file.parentFile
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0L)
    /** Moves on every save, so an open writer re-reads text that quick add kept or saved (D3 review). */
    val version: kotlinx.coroutines.flow.StateFlow<Long> = _version
    override fun load(key: String): String = kv.getString(key).orEmpty()
    override fun save(key: String, text: String) {
        // A full or read-only disk must not crash typing (the field keeps the text; only the backup copy fails).
        runCatching { if (text.isEmpty()) kv.remove(key) else kv.putString(key, text) }.onFailure { logW("loopdo_drafts", "couldn't keep the draft", it) }
        _version.value++
    }
    override fun keys(): Set<String> = kv.keys()

    /**
     * Another account signs in on this laptop (the database is being cleared for it): the unsaved text of [from] is
     * set aside in its own file, never shown to or saved under [to]; [to]'s own text set aside earlier comes back.
     * Copied first, removed after, so a crash in between leaves a copy, never nothing (ultrareview).
     */
    fun switchAccount(from: String, to: String) {
        val aside = { user: String -> FileKeyValue(File(dir, "drafts.${user.filter { it.isLetterOrDigit() || it == '-' }}.json")) }
        val mine = kv.keys().associateWith { kv.getString(it) }.filterValues { !it.isNullOrEmpty() }
        if (mine.isNotEmpty()) { val a = aside(from); mine.forEach { (k, v) -> a.putString(k, v!!) } }
        kv.keys().forEach { kv.remove(it) }
        val theirs = aside(to)
        theirs.keys().forEach { k -> theirs.getString(k)?.let { kv.putString(k, it) } }
        theirs.keys().forEach { theirs.remove(it) }
        _version.value++
    }
}
