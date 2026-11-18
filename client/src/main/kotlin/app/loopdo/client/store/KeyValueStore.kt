package app.loopdo.client.store

/** Small persistent numbers (D2): SharedPreferences on the phone, a JSON file on the laptop. */
interface KeyValueStore {
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}
