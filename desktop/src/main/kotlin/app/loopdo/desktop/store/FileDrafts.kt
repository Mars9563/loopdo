package app.loopdo.desktop.store

import app.loopdo.client.log.logW
import app.loopdo.client.popup.PopupDrafts
import app.loopdo.client.sync.accountTag
import app.loopdo.client.sync.switchDrafts
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

    /** Another account takes over this laptop: [from]'s unsaved text moves to drafts.<from>.json, [to]'s comes back. */
    fun switchAccount(from: String, to: String) {
        switchDrafts(this, { u -> FilePopupDrafts(File(dir, "drafts.${accountTag(u)}.json")) }, from, to)
        _version.value++
    }
}
