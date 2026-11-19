package app.loopdo.android.sync


import app.loopdo.client.sync.KtorUploadApi
import app.loopdo.client.sync.LoopDoConnector
import app.loopdo.client.sync.LoopDoSchema
import app.loopdo.client.sync.PowerSyncDraftStore
import app.loopdo.client.sync.PowerSyncUploadState
import app.loopdo.client.sync.SetAsideNotices
import app.loopdo.android.popup.PrefsPopupDrafts
import app.loopdo.client.sync.accountTag
import app.loopdo.client.sync.switchDrafts
import app.loopdo.client.sync.LastUserStore
import app.loopdo.client.sync.PowerSyncSessionDb
import app.loopdo.client.sync.SessionSync
import app.loopdo.client.sync.Uploader

import android.content.Context
import android.util.Log
import app.loopdo.client.auth.AuthRepository
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
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
    private val anchors: app.loopdo.client.time.AnchorRefresher? = null,
    /** Trusted time, so the 24 h server-error patience can't be skipped by changing the phone's clock. */
    now: () -> Instant = Instant::now,
) {
    val db: PowerSyncDatabase = PowerSyncDatabase(DatabaseDriverFactory(context), LoopDoSchema.schema, dbFilename = "loopdo.db")
    private val prefs = context.getSharedPreferences("loopdo_sync", Context.MODE_PRIVATE)
    /** Uploads the server refused (and the phone undid), kept until the today screen shows them. */
    val notices = SetAsideNotices()
    private val http = HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 15_000 } }
    private val uploader = Uploader(
        KtorUploadApi(http) { auth.freshToken() },
        PowerSyncDraftStore(db),
        PowerSyncUploadState(db),
        notices::add,
        now,
    )
    private val connector = LoopDoConnector(auth, uploader)

    /** The account rule, shared with the laptop in client/ (SessionSync); unsaved text is set aside per account. */
    private val session = SessionSync(
        auth.session, PowerSyncSessionDb(db, connector),
        object : LastUserStore {
            override fun get() = prefs.getString(LAST_USER, null)
            override fun set(user: String) = prefs.edit().putString(LAST_USER, user).apply()
        },
        scope, SWITCH_REFUSED, onConnected = { refreshAnchor() },
        onAccountSwitch = { from, to ->
            val ctx = context.applicationContext
            switchDrafts(PrefsPopupDrafts(ctx), { u -> PrefsPopupDrafts(ctx, "loopdo_popup_drafts_${accountTag(u)}") }, from, to)
        },
    )
    /** Non-null while a different account signed in over unsynced changes: the app must not open. */
    val blocked: kotlinx.coroutines.flow.StateFlow<String?> = session.blocked

    init {
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

    private companion object {
        const val LAST_USER = "last_user_id"
        const val SWITCH_REFUSED = "this phone still has changes from another account that haven't reached the server. " +
            "sign out, then sign in with that account while online so they can sync first."
    }
}
