package app.loopdo.android.sync

import android.content.Context
import android.util.Log
import app.loopdo.android.auth.AuthRepository
import app.loopdo.android.auth.AuthState
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The phone's database and its sync. Connects while signed in. A *different* account
 * signing in clears the previous one's local data first; signing out only disconnects,
 * so changes not yet uploaded are never lost.
 */
class SyncModule(
    context: Context,
    private val auth: AuthRepository,
    scope: CoroutineScope,
    private val anchors: app.loopdo.android.time.AnchorRefresher? = null,
    /** Trusted time, so the 24 h server-error patience can't be skipped by changing the phone's clock. */
    now: () -> Instant = Instant::now,
) {
    val db: PowerSyncDatabase = PowerSyncDatabase(DatabaseDriverFactory(context), LoopDoSchema.schema, dbFilename = "loopdo.db")
    private val prefs = context.getSharedPreferences("loopdo_sync", Context.MODE_PRIVATE)
    /** Uploads the server refused (and the phone undid), kept until the today screen shows them. */
    val notices = SetAsideNotices()
    private val blockedState = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Non-null while a different account signed in over unsynced changes: the app must not open. */
    val blocked: kotlinx.coroutines.flow.StateFlow<String?> = blockedState
    private val http = HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 15_000 } }
    private val uploader = Uploader(
        KtorUploadApi(http) { auth.freshToken() },
        PowerSyncDraftStore(db),
        PowerSyncUploadState(db),
        notices::add,
        now,
    )
    private val connector = LoopDoConnector(auth, uploader)

    init {
        scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e("loopdo_sync", "sync loop failed", e) }) {
            auth.session.map { (it as? AuthState.SignedIn)?.userId?.toString() ?: it::class.simpleName }
                .distinctUntilChanged()
                .collect { key ->
                    Log.i("loopdo_sync", "auth changed: $key")
                    when (val s = auth.session.value) {
                        is AuthState.SignedIn -> {
                            val user = s.userId.toString()
                            val last = prefs.getString(LAST_USER, null)
                            when (accountSwitch(last, user, unsyncedChanges = last != null && last != user && hasUnsynced())) {
                                SwitchAction.REFUSE -> {
                                    Log.w("loopdo_sync", "account switch refused: unsynced changes from the previous account")
                                    blockedState.value = SWITCH_REFUSED
                                    return@collect
                                }
                                SwitchAction.CLEAR_THEN_CONNECT -> db.disconnectAndClear()
                                SwitchAction.CONNECT -> Unit
                            }
                            blockedState.value = null
                            prefs.edit().putString(LAST_USER, user).apply()
                            Log.i("loopdo_sync", "connecting")
                            db.connect(connector)
                            refreshAnchor()
                        }
                        AuthState.SignedOut -> { blockedState.value = null; db.disconnect() }
                        AuthState.Loading -> Unit
                    }
                }
        }
        scope.launch {
            db.currentStatus.asFlow().collect {
                Log.i("loopdo_sync", "connected=${it.connected} hasSynced=${it.hasSynced} uploading=${it.uploading} error=${it.anyError}")
            }
        }
    }

    /** Fresh signed server time (anchors come only from /api/time). Offline is fine: the old anchor stays. */
    fun refreshAnchor() {
        val r = anchors ?: return
        anchorScope.launch {
            runCatching { r.refresh() }
                .onSuccess { Log.i("loopdo_sync", "time anchor refreshed") }
                .onFailure { Log.i("loopdo_sync", "time anchor not refreshed: ${it.message}") }
        }
    }

    private val anchorScope = scope

    private suspend fun hasUnsynced(): Boolean = hasUnsyncedWork(db)

    private companion object {
        const val LAST_USER = "last_user_id"
        const val SWITCH_REFUSED = "this phone still has changes from another account that haven't reached the server. " +
            "sign out, then sign in with that account while online so they can sync first."
    }
}

/** Queued uploads, drafts the server hasn't got, or photos not yet in R2 (M6): an account switch must not wipe them. */
internal suspend fun hasUnsyncedWork(db: com.powersync.PowerSyncDatabase): Boolean =
    db.getNextCrudTransaction() != null ||
        db.get("SELECT count(*) FROM drafts") { it.getLong(0)!! } > 0 ||
        db.get("SELECT count(*) FROM photo_files WHERE uploaded = 0") { it.getLong(0)!! } > 0
