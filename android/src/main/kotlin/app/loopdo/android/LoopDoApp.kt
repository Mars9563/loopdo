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
    private val http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 15_000 }
    }
    private val token = { (auth.session.value as? app.loopdo.android.auth.AuthState.SignedIn)?.accessToken }
    private val timeApi = app.loopdo.android.time.KtorTimeApi(http, token)
    val sync = app.loopdo.android.sync.SyncModule(context, auth, scope, app.loopdo.android.time.AnchorRefresher(timeApi, anchorStore, clocks))
    val onboarding = app.loopdo.android.onboarding.Onboarding(
        app.loopdo.android.onboarding.KtorSettingsApi(http, token),
        app.loopdo.android.onboarding.PowerSyncProfileWriter(sync.db) { (auth.session.value as? app.loopdo.android.auth.AuthState.SignedIn)?.userId },
        logFailure = { android.util.Log.w("loopdo_onboarding", "setup failed", it) },
    )
    private val todayData = app.loopdo.android.today.TodayData(sync.db)
    private val actions = app.loopdo.android.today.Actions(
        sync.db, trustedClock, evidence,
        dateAt = { t -> app.loopdo.shared.day.dateResolver(todayData.versionsNow())(t) },
        userId = { (auth.session.value as? app.loopdo.android.auth.AuthState.SignedIn)?.userId },
    )
    fun todayViewModel() = app.loopdo.android.today.TodayViewModel(todayData, actions, sync.setAside) { trustedClock.now().instant }
}

class LoopDoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
