package app.loopdo.desktop.sync

import app.loopdo.client.auth.AuthRepository
import app.loopdo.client.auth.AuthState
import app.loopdo.client.log.logI
import app.loopdo.client.log.logW
import app.loopdo.client.log.logE
import app.loopdo.client.sync.KtorUploadApi
import app.loopdo.client.sync.LoopDoConnector
import app.loopdo.client.sync.LoopDoSchema
import app.loopdo.client.sync.PowerSyncDraftStore
import app.loopdo.client.sync.PowerSyncUploadState
import app.loopdo.client.sync.SetAsideNotices
import app.loopdo.client.sync.SwitchAction
import app.loopdo.client.sync.Uploader
import app.loopdo.client.sync.accountSwitch
import app.loopdo.client.sync.hasUnsyncedWork
import app.loopdo.client.time.AnchorRefresher
import app.loopdo.desktop.store.FileKeyValue
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/** The laptop's database file: the phone's schema, PowerSync's bundled SQLite. */
fun openDatabase(dataDir: File): PowerSyncDatabase {
    dataDir.mkdirs()
    return PowerSyncDatabase(DatabaseDriverFactory(), LoopDoSchema.schema, dbFilename = "loopdo.db", dbDirectory = dataDir.path)
}

/**
 * The laptop's sync, the same rules as the phone's SyncModule: connects while signed in; a *different* account
 * signing in clears the previous one's local data first, and is refused while that data hasn't reached the server;
 * signing out only disconnects, so nothing waiting to upload is lost.
 */
class DesktopSync(
    dataDir: File,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
    private val anchors: AnchorRefresher?,
    now: () -> Instant = Instant::now,
    val db: PowerSyncDatabase = openDatabase(dataDir),
    http: HttpClient = HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 15_000 } },
) {
    private val keys = FileKeyValue(File(dataDir, "sync.json"))
    val notices = SetAsideNotices()
    private val blockedState = MutableStateFlow<String?>(null)
    /** Non-null while a different account signed in over unsynced changes: the app must not open. */
    val blocked: StateFlow<String?> = blockedState
    private val uploader = Uploader(KtorUploadApi(http) { auth.freshToken() }, PowerSyncDraftStore(db), PowerSyncUploadState(db), notices::add, now)
    private val connector = LoopDoConnector(auth, uploader)

    init {
        scope.launch(CoroutineExceptionHandler { _, e -> logE(TAG, "sync loop failed", e) }) {
            auth.session.map { (it as? AuthState.SignedIn)?.userId?.toString() ?: it::class.simpleName }
                .distinctUntilChanged()
                .collect {
                    when (val s = auth.session.value) {
                        is AuthState.SignedIn -> {
                            val user = s.userId.toString()
                            val last = keys.getString(LAST_USER)
                            when (accountSwitch(last, user, unsyncedChanges = last != null && last != user && hasUnsyncedWork(db))) {
                                SwitchAction.REFUSE -> {
                                    logW(TAG, "account switch refused: unsynced changes from the previous account")
                                    blockedState.value = SWITCH_REFUSED
                                    return@collect
                                }
                                SwitchAction.CLEAR_THEN_CONNECT -> db.disconnectAndClear()
                                SwitchAction.CONNECT -> Unit
                            }
                            blockedState.value = null
                            keys.putString(LAST_USER, user)
                            logI(TAG, "connecting")
                            db.connect(connector)
                            refreshAnchor()
                        }
                        AuthState.SignedOut -> { blockedState.value = null; db.disconnect() }
                        AuthState.Loading -> Unit
                    }
                }
        }
    }

    /** Fresh signed server time (anchors come only from /api/time). Offline is fine: the old anchor stays. */
    fun refreshAnchor() {
        val r = anchors ?: return
        scope.launch {
            runCatching { r.refresh() }
                .onSuccess { logI(TAG, "time anchor refreshed") }
                .onFailure { logI(TAG, "time anchor not refreshed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "loopdo_sync"
        const val LAST_USER = "last_user_id"
        const val SWITCH_REFUSED = "this laptop still has changes from another account that haven't reached the server. " +
            "sign out, then sign in with that account while online so they can sync first."
    }
}
