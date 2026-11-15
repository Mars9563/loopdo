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
class SyncModule(context: Context, private val auth: AuthRepository, scope: CoroutineScope) {
    val db: PowerSyncDatabase = PowerSyncDatabase(DatabaseDriverFactory(context), LoopDoSchema.schema, dbFilename = "loopdo.db")
    private val prefs = context.getSharedPreferences("loopdo_sync", Context.MODE_PRIVATE)
    private val http = HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 15_000 } }
    private val uploader = Uploader(
        KtorUploadApi(http) { (auth.session.value as? AuthState.SignedIn)?.accessToken },
        PowerSyncDraftStore(db),
        PowerSyncUploadState(db),
        Instant::now,
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
                            if (prefs.getString(LAST_USER, null).let { it != null && it != user }) db.disconnectAndClear()
                            prefs.edit().putString(LAST_USER, user).apply()
                            Log.i("loopdo_sync", "connecting")
                            db.connect(connector)
                            Log.i("loopdo_sync", "connect() returned")
                        }
                        AuthState.SignedOut -> db.disconnect()
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

    private companion object {
        const val LAST_USER = "last_user_id"
    }
}
