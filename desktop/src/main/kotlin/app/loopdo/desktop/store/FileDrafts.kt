package app.loopdo.desktop.store

import app.loopdo.client.popup.PopupDrafts
import java.io.File

/** Unsaved journal text (the phone's PopupDrafts), in the data folder so closing the window never loses it. */
class FilePopupDrafts(file: File) : PopupDrafts {
    private val kv = FileKeyValue(file)
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0L)
    /** Moves on every save, so an open writer re-reads text that quick add kept or saved (D3 review). */
    val version: kotlinx.coroutines.flow.StateFlow<Long> = _version
    override fun load(key: String): String = kv.getString(key).orEmpty()
    override fun save(key: String, text: String) { if (text.isEmpty()) kv.remove(key) else kv.putString(key, text); _version.value++ }
    override fun keys(): Set<String> = kv.keys()
}
