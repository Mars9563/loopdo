package app.loopdo.android

import android.app.Application
import app.loopdo.android.auth.AuthRepository
import app.loopdo.android.auth.GoogleSignIn
import app.loopdo.android.auth.SupabaseAuthBackend
import app.loopdo.android.config.AppConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency graph: one of each, alive for the app's lifetime. */
class AppGraph(context: android.content.Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val supabase = createSupabaseClient(AppConfig.supabaseUrl, AppConfig.publishableKey) { install(Auth) }
    val auth = AuthRepository(SupabaseAuthBackend(supabase, scope), scope)
    val google = GoogleSignIn(AppConfig.googleWebClientId)
    val clocks = app.loopdo.android.time.AndroidDeviceClocks(context)
    val anchorStore = app.loopdo.android.time.PrefsAnchorStore(context)
    val trustedClock = app.loopdo.android.time.TrustedClock(clocks, anchorStore)
    val evidence = app.loopdo.android.time.EvidenceFactory(clocks, anchorStore)
    private val timeApi = app.loopdo.android.time.KtorTimeApi(io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)) {
        (auth.session.value as? app.loopdo.android.auth.AuthState.SignedIn)?.accessToken
    }
    val sync = app.loopdo.android.sync.SyncModule(context, auth, scope, app.loopdo.android.time.AnchorRefresher(timeApi, anchorStore, clocks))
}

class LoopDoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
