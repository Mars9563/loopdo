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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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
    val supabase = createSupabaseClient(AppConfig.supabaseUrl, AppConfig.publishableKey) {
        install(Auth) {
            // Off: on Android it stops token refresh and reports "Initializing" whenever the app is in the
            // background, which stalled background sync and blanked the widget (M4 Task 8).
            enableLifecycleCallbacks = false
        }
    }
    val auth = AuthRepository(SupabaseAuthBackend(supabase, scope), scope)
    val google = GoogleSignIn(AppConfig.googleWebClientId)
    val clocks = app.loopdo.android.time.AndroidDeviceClocks(context)
    val anchorStore = app.loopdo.android.time.PrefsAnchorStore(context)
    val trustedClock = app.loopdo.android.time.TrustedClock(clocks, anchorStore)
    val evidence = app.loopdo.android.time.EvidenceFactory(clocks, anchorStore)
    private val http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 15_000 }
    }
    private val token: suspend () -> String? = { auth.freshToken() }
    private val timeApi = app.loopdo.android.time.KtorTimeApi(http, token)
    val sync = app.loopdo.android.sync.SyncModule(
        context, auth, scope, app.loopdo.android.time.AnchorRefresher(timeApi, anchorStore, clocks),
    ) { trustedClock.now().instant }
    val onboarding = app.loopdo.android.onboarding.Onboarding(
        app.loopdo.android.onboarding.KtorSettingsApi(http, token),
        app.loopdo.android.onboarding.PowerSyncProfileWriter(sync.db) { (auth.session.value as? app.loopdo.android.auth.AuthState.SignedIn)?.userId },
        logFailure = { android.util.Log.w("loopdo_onboarding", "setup failed", it) },
    )
    val todayData = app.loopdo.android.today.TodayData(sync.db)
    val journalActions = app.loopdo.android.journal.JournalActions(
        sync.db, trustedClock,
        dateAt = { t -> app.loopdo.shared.day.dateResolver(todayData.versionsNow())(t) },
        settingsAt = { t -> app.loopdo.shared.day.settingsAt(todayData.versionsNow(), t) },
        userId = { (auth.session.value as? app.loopdo.android.auth.AuthState.SignedIn)?.userId },
    )
    val actions = app.loopdo.android.today.Actions(
        sync.db, trustedClock, evidence,
        dateAt = { t -> app.loopdo.shared.day.dateResolver(todayData.versionsNow())(t) },
        userId = { (auth.session.value as? app.loopdo.android.auth.AuthState.SignedIn)?.userId },
        series = { draft -> taskActions.saveTask(null, draft) },
        journal = journalActions,
    )
    private val appContext = context.applicationContext

    /** M6: the optional app lock (boot clock, so a changed wall clock can't skip it). */
    val appLock = app.loopdo.android.lock.AppLock(app.loopdo.android.lock.PrefsLockStore(context.applicationContext)) { android.os.SystemClock.elapsedRealtime() }

    // M6 photos: rows sync with PowerSync; files go to R2 through the API's signed URLs.
    private val photoApi = app.loopdo.android.journal.KtorPhotoApi(http, token)
    val photoUploader = app.loopdo.android.journal.PhotoUploader(
        photoApi,
        pending = {
            sync.db.getAll(
                "SELECT f.id, f.path, p.entry_id, p.bytes FROM photo_files f JOIN journal_photos p ON p.id = f.id WHERE f.uploaded = 0",
            ) { app.loopdo.android.journal.PendingPhoto(java.util.UUID.fromString(it.getString(0)!!), it.getString(1)!!, it.getString(2)!!, it.getLong(3) ?: 0L) }
        },
        onError = { android.util.Log.i("loopdo_photos", "upload later: ${it.message}") },
    ) { id -> sync.db.execute("UPDATE photo_files SET uploaded = 1 WHERE id = ?", listOf(id.toString())) }
    val photoCache = app.loopdo.android.journal.PhotoCache(java.io.File(appContext.cacheDir, "photos"), photoApi)
    suspend fun pendingPhotoCount(): Long = sync.db.get(app.loopdo.android.sync.PENDING_PHOTOS_SQL) { it.getLong(0) ?: 0L }
    /** Frees the phone's own copies of photos already in R2 for 90 days (they download again when opened). */
    suspend fun freeOldPhotoCopies() {
        val own = sync.db.getAll("SELECT path, uploaded FROM photo_files WHERE coalesce(path, '') <> ''") {
            val f = java.io.File(it.getString(0)!!)
            app.loopdo.android.journal.OwnPhoto(f.path, it.getLong(1) == 1L, java.time.Instant.ofEpochMilli(f.lastModified()))
        }
        app.loopdo.android.journal.photosToFree(own.filter { java.io.File(it.path).exists() }, trustedClock.now().instant).forEach { java.io.File(it).delete() }
    }
    fun uploadPhotosSoon() = runCatching { app.loopdo.android.journal.PhotoWorker.enqueue(appContext) }

    /** Widget taps: the same actions as the app, then a redraw (Task 5 adds the refresh schedule). */
    private val widgetRefresh = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val widgetRefresher = app.loopdo.android.widget.WidgetRefresher(appContext)

    val taskActions = app.loopdo.android.tasks.TaskActions(
        sync.db,
        userId = { (auth.session.value as? app.loopdo.android.auth.AuthState.SignedIn)?.userId },
        dateAt = { t -> app.loopdo.shared.day.dateResolver(todayData.versionsNow())(t) },
        clock = trustedClock,
        evidence = evidence,
    )

    /**
     * Creates repeating tasks' occurrences up to today. Only once the app is ready (signed in,
     * synced, this account's data): before the first sync a series would look new. A no-op when
     * nothing is due, so the redraw it causes ends there.
     */
    private suspend fun ensureInstances() {
        val ready = widgetSnapshot() as? app.loopdo.android.widget.WidgetState.Ready ?: return
        runCatching { taskActions.ensureInstances(ready.model.date) }
            .onFailure { android.util.Log.w("loopdo_tasks", "creating repeating tasks failed", it) }
    }

    /**
     * Rebuilds an open widget session now, starts a closed one, and re-arms the day-end and 15-minute alarms.
     * It runs at app start, at day end, every ~15 minutes and after every change, so repeating tasks'
     * occurrences are created here first (M5).
     */
    suspend fun redrawWidget() {
        ensureInstances()
        widgetRefresh.tryEmit(Unit)
        app.loopdo.android.widget.LoopDoWidget().updateAll(appContext)
        val ready = widgetSnapshot() as? app.loopdo.android.widget.WidgetState.Ready
        widgetRefresher.schedule(ready?.model?.dayEndAt, trustedClock.now().instant)
    }

    init {
        // Changes that arrive while no widget session is open (sync, the app) still reach the widget.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch { todayData.rows().debounce(500).collect { runCatching { redrawWidget() } } }
        // Sign-in/out: show (or hide) the data even when no widget session is open.
        scope.launch { auth.session.drop(1).collect { runCatching { redrawWidget() } } }
        scope.launch { sync.blocked.drop(1).collect { runCatching { redrawWidget() } } }
        // M6.5: a day's text saved before lines were rows (also arriving by sync) becomes rows, once. Watches the
        // settings too, since the move needs the day settings (they may arrive after the entries on a new phone).
        scope.launch {
            sync.db.watch(
                "SELECT (SELECT count(*) FROM journal_entries WHERE trim(coalesce(text, '')) <> ''), (SELECT count(*) FROM day_settings_versions)",
            ) { (it.getLong(0) ?: 0L) to (it.getLong(1) ?: 0L) }.collect { rows ->
                val (old, versions) = rows.firstOrNull() ?: return@collect
                if (old > 0 && versions > 0) runCatching { journalActions.moveOldText() }
                    .onFailure { android.util.Log.w("loopdo_journal", "moving old journal text failed", it) }
            }
        }
        // Background sync every ~15 min while online (owner, 2026-11-16).
        runCatching { app.loopdo.android.sync.SyncWorker.schedule(appContext) }
        // Photos taken earlier (offline, or before a restart) go up as soon as there is a network.
        uploadPhotosSoon()
        // The app lock counts time in the background from the whole app (not one screen).
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = appLock.onBackground()
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = appLock.onForeground()
        })
    }

    /** Live widget state: the database's changes plus explicit redraws. */
    fun widgetStates() = app.loopdo.android.widget.widgetStates(
        app.loopdo.android.widget.widgetTriggers(todayData.rows(), auth.session, sync.blocked, widgetRefresh),
    ) { widgetSnapshot() }

    val widgetCommands by lazy {
        app.loopdo.android.widget.WidgetCommands(
            actions,
            readyOn = { (widgetSnapshot() as? app.loopdo.android.widget.WidgetState.Ready)?.model?.date },
        ) { redrawWidget() }
    }

    /** M7: the phone's own reminders (alarms + notifications), ready on the same day the widget is. */
    val reminders by lazy {
        app.loopdo.android.notify.ReminderScheduler(
            app.loopdo.android.notify.ReminderData(sync.db),
            app.loopdo.android.notify.ReminderStore(appContext.getSharedPreferences("reminders", android.content.Context.MODE_PRIVATE)),
            app.loopdo.android.notify.AndroidAlarmSetter(appContext),
            app.loopdo.android.notify.AndroidPoster(appContext),
            today = { (widgetSnapshot() as? app.loopdo.android.widget.WidgetState.Ready)?.model?.date },
            trustedNow = { trustedClock.now().instant },
            elapsedNow = { android.os.SystemClock.elapsedRealtime() },
        )
    }

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

    /** Today for a pop-up, or null when the app isn't ready (same gate as the widget). */
    suspend fun todayForPopup(): app.loopdo.android.today.TodayModel? =
        if (widgetSnapshot() is app.loopdo.android.widget.WidgetState.Ready) todayData.rows().first().toModel(trustedClock.now().instant)?.first else null

    val habitActions = app.loopdo.android.habits.HabitActions(
        sync.db, trustedClock, evidence,
        dateAt = { t -> app.loopdo.shared.day.dateResolver(todayData.versionsNow())(t) },
        userId = { (auth.session.value as? app.loopdo.android.auth.AuthState.SignedIn)?.userId },
    )

    fun habitsViewModel() = app.loopdo.android.habits.HabitsViewModel(todayData.rows(), habitActions) { trustedClock.now().instant }

    fun tasksViewModel() = app.loopdo.android.tasks.TasksViewModel(app.loopdo.android.tasks.TaskData(sync.db), taskActions)

    fun journalViewModel() = app.loopdo.android.journal.JournalViewModel(
        todayData.rows(), app.loopdo.android.journal.JournalData(sync.db), journalActions, { trustedClock.now().instant },
        photoFile = { id, path -> photoCache.file(id, path) }, uploadSoon = { uploadPhotosSoon() },
        drafts = app.loopdo.android.journal.LineDrafts(app.loopdo.android.popup.PrefsPopupDrafts(appContext)),
    )

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
