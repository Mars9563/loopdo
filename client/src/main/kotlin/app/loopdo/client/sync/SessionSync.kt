package app.loopdo.client.sync

import app.loopdo.client.auth.AuthState
import app.loopdo.client.log.logE
import app.loopdo.client.log.logI
import app.loopdo.client.log.logW
import app.loopdo.client.popup.PopupDrafts
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// The account rule both devices follow (it was copied between the phone's SyncModule and the laptop's DesktopSync;
// ultrareview 2026-11-19): connect while signed in; a *different* account clears the previous one's local data
// first, never while some of it hasn't reached the server; signing out only disconnects.

/** What the account rule does to the local database. */
interface SessionDb {
    suspend fun connect()
    suspend fun disconnect()
    /** Disconnects and deletes every local row (another account takes over). */
    suspend fun disconnectAndClear()
    /** Queued uploads, unsent drafts or photos not yet in R2: an account switch must not wipe them. */
    suspend fun hasUnsyncedWork(): Boolean
}

class PowerSyncSessionDb(private val db: PowerSyncDatabase, private val connector: LoopDoConnector) : SessionDb {
    override suspend fun connect() = db.connect(connector)
    override suspend fun disconnect() = db.disconnect()
    override suspend fun disconnectAndClear() = db.disconnectAndClear()
    override suspend fun hasUnsyncedWork() = hasUnsyncedWork(db)
}

/** The last account signed in on this device (SharedPreferences on the phone, a file on the laptop). */
interface LastUserStore {
    fun get(): String?
    fun set(user: String)
}

class SessionSync(
    session: StateFlow<AuthState>,
    private val db: SessionDb,
    private val lastUser: LastUserStore,
    scope: CoroutineScope,
    /** Shown instead of the app while another account's unsynced changes are on this device. */
    private val refusedMessage: String,
    /** Connected (fresh signed server time, …). */
    private val onConnected: () -> Unit = {},
    /** Another account took over after its data was cleared: set [from]'s device-only data (unsaved text) aside. */
    private val onAccountSwitch: (from: String, to: String) -> Unit = { _, _ -> },
) {
    private val blockedState = MutableStateFlow<String?>(null)
    /** Non-null while a different account signed in over unsynced changes: the app must not open. */
    val blocked: StateFlow<String?> = blockedState

    init {
        scope.launch(CoroutineExceptionHandler { _, e -> logE(TAG, "sync loop failed", e) }) {
            session.map { (it as? AuthState.SignedIn)?.userId?.toString() ?: it::class.simpleName }
                .distinctUntilChanged()
                .collect { key ->
                    logI(TAG, "auth changed: $key")
                    when (val s = session.value) {
                        is AuthState.SignedIn -> signedIn(s.userId.toString())
                        AuthState.SignedOut -> { blockedState.value = null; db.disconnect() }
                        AuthState.Loading -> Unit
                    }
                }
        }
    }

    private suspend fun signedIn(user: String) {
        val last = lastUser.get()
        when (accountSwitch(last, user, unsyncedChanges = last != null && last != user && db.hasUnsyncedWork())) {
            SwitchAction.REFUSE -> {
                logW(TAG, "account switch refused: unsynced changes from the previous account")
                blockedState.value = refusedMessage
                return
            }
            SwitchAction.CLEAR_THEN_CONNECT -> {
                db.disconnectAndClear()
                runCatching { onAccountSwitch(last!!, user) }.onFailure { logW(TAG, "setting the last account's drafts aside failed", it) }
            }
            SwitchAction.CONNECT -> Unit
        }
        blockedState.value = null
        lastUser.set(user)
        logI(TAG, "connecting")
        db.connect()
        onConnected()
    }

    private companion object { const val TAG = "loopdo_sync" }
}

/**
 * Another account takes over this device: every unsaved draft of [from] moves into [asideOf] ([from]) — copied first,
 * removed after, so a crash leaves a copy, never nothing — and [to]'s drafts set aside earlier come back. One
 * account's unsaved text is never shown to, or saved under, another (ultrareview, both devices).
 */
fun switchDrafts(current: PopupDrafts, asideOf: (String) -> PopupDrafts, from: String, to: String) {
    val mine = current.keys().associateWith { current.load(it) }.filterValues { it.isNotEmpty() }
    if (mine.isNotEmpty()) { val a = asideOf(from); mine.forEach { (k, v) -> a.save(k, v) } }
    current.keys().forEach { current.save(it, "") }
    val theirs = asideOf(to)
    val back = theirs.keys().associateWith { theirs.load(it) }.filterValues { it.isNotEmpty() }
    back.forEach { (k, v) -> current.save(k, v) }
    back.keys.forEach { theirs.save(it, "") }
}

/** A file or prefs name part from an account id (UUIDs pass through unchanged). */
fun accountTag(user: String) = user.filter { it.isLetterOrDigit() || it == '-' }
