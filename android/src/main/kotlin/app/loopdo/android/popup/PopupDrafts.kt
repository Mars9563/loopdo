package app.loopdo.android.popup

import app.loopdo.client.popup.PopupDrafts

import android.content.Context

class PrefsPopupDrafts(context: Context, name: String = "loopdo_popup_drafts") : PopupDrafts {
    /** [name]: the live drafts, or "loopdo_popup_drafts_<account>" for an account's text set aside on a switch. */
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun load(key: String): String = prefs.getString(key, null).orEmpty()
    override fun keys(): Set<String> = prefs.all.keys
    override fun save(key: String, text: String) {
        prefs.edit().apply { if (text.isEmpty()) remove(key) else putString(key, text) }.apply()
    }
}
