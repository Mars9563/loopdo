package app.loopdo.android.settings

import android.content.Context
import app.loopdo.client.store.KeyValueStore

/** [KeyValueStore] over one SharedPreferences file (same file and keys as before D2). */
class PrefsKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
