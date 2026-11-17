package app.loopdo.android.lock

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the lock switch is kept (this phone only; off by default). */
interface LockStore { var enabled: Boolean }

class PrefsLockStore(context: Context) : LockStore {
    private val prefs = context.getSharedPreferences("loopdo_lock", Context.MODE_PRIVATE)
    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }
}

/**
 * The optional app lock (owner, 2026-11-17): when on, the app asks for fingerprint / the phone's PIN on a cold
 * start and after ≥ [AWAY_MS] in the background. [elapsed] is the boot clock (changing the wall clock can't skip it).
 * The widget and its pop-ups show no journal text and are not locked.
 */
class AppLock(private val store: LockStore, private val elapsed: () -> Long) {
    private val _locked = MutableStateFlow(store.enabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var backgroundAt: Long? = null

    val enabled: Boolean get() = store.enabled

    fun setEnabled(on: Boolean) {
        store.enabled = on
        if (!on) _locked.value = false
    }

    fun onBackground() { if (store.enabled) backgroundAt = elapsed() }

    fun onForeground() {
        val since = backgroundAt
        backgroundAt = null
        if (store.enabled && since != null && elapsed() - since >= AWAY_MS) _locked.value = true
    }

    fun unlocked() { _locked.value = false; backgroundAt = null }

    companion object {
        const val AWAY_MS = 60_000L
    }
}
