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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.glance.appwidget.updateAll
import app.loopdo.android.onboarding.hasDaySettings
import app.loopdo.android.onboarding.isOnboarded
import app.loopdo.android.today.toModel

/** Manual dependency graph: one of each, alive for the app's lifetime. */
class AppGraph(context: android.content.Context) {
    // A failure in a background job is logged, never a crash (e.g. a network call that throws).
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("loopdo", "background job failed", e) },
    )
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
    val sync = app.loopdo.android.sync.SyncModule(
        context, auth, scope, app.loopdo.android.time.AnchorRefresher(timeApi, anchorStore, clocks),
    ) { trustedClock.now().instant }
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
    private val appContext = context.applicationContext

    /** Widget taps: the same actions as the app, then a redraw (Task 5 adds the refresh schedule). */
    private val widgetRefresh = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Rebuilds an open widget session now and makes sure a closed one is started (Task 5 adds the schedule). */
    suspend fun redrawWidget() {
        widgetRefresh.tryEmit(Unit)
        app.loopdo.android.widget.LoopDoWidget().updateAll(appContext)
    }

    /** Live widget state: the database's changes plus explicit redraws. */
    fun widgetStates() = app.loopdo.android.widget.widgetStates(
        kotlinx.coroutines.flow.merge(todayData.rows().map { }, widgetRefresh),
    ) { widgetSnapshot() }

    val widgetCommands by lazy { app.loopdo.android.widget.WidgetCommands(actions) { redrawWidget() } }

    /** What the widget shows right now: nothing tickable until the app is ready (M3 start rules). */
    suspend fun widgetSnapshot(): app.loopdo.android.widget.WidgetState {
        if (sync.blocked.value != null) return app.loopdo.android.widget.WidgetState.SignedOut // another account's data: show none of it
        val session = kotlinx.coroutines.withTimeoutOrNull(3_000) {
            auth.session.first { it !is app.loopdo.android.auth.AuthState.Loading }
        } ?: auth.session.value
        val start = app.loopdo.android.onboarding.startState(
            session, sync.db.currentStatus.hasSynced, sync.db.hasDaySettings().first(), sync.db.isOnboarded().first(),
        )
        val built = if (start == app.loopdo.android.onboarding.StartState.Ready) todayData.rows().first().toModel(trustedClock.now().instant) else null
        return app.loopdo.android.widget.widgetState(start, built?.first, built?.second)
    }

    fun todayViewModel() = app.loopdo.android.today.TodayViewModel(todayData.rows(), actions, sync.notices) { trustedClock.now().instant }
}

class LoopDoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
