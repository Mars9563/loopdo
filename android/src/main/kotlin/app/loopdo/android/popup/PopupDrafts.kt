package app.loopdo.android.popup

import android.content.Context

/** Text typed into a pop-up and not saved yet. It survives Back, a tap outside, a failed save, or the system closing the pop-up. */
interface PopupDrafts {
    fun load(key: String): String
    /** An empty [text] removes the draft. */
    fun save(key: String, text: String)
    fun keys(): Set<String>
}

class PrefsPopupDrafts(context: Context) : PopupDrafts {
    private val prefs = context.getSharedPreferences("loopdo_popup_drafts", Context.MODE_PRIVATE)
    override fun load(key: String): String = prefs.getString(key, null).orEmpty()
    override fun keys(): Set<String> = prefs.all.keys
    override fun save(key: String, text: String) {
        prefs.edit().apply { if (text.isEmpty()) remove(key) else putString(key, text) }.apply()
    }
}
