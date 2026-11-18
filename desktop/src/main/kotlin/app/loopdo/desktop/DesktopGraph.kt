package app.loopdo.desktop

import app.loopdo.client.auth.AuthRepository
import app.loopdo.client.auth.AuthState
import app.loopdo.client.auth.SupabaseAuthBackend
import app.loopdo.client.config.AppConfig
import app.loopdo.client.journal.JournalActions
import app.loopdo.client.log.logE
import app.loopdo.client.tasks.TaskActions
import app.loopdo.client.time.AnchorRefresher
import app.loopdo.client.time.EvidenceFactory
import app.loopdo.client.time.KtorTimeApi
import app.loopdo.client.time.TrustedClock
import app.loopdo.client.today.Actions
import app.loopdo.client.today.TodayData
import app.loopdo.client.today.TodayViewModel
import app.loopdo.desktop.auth.FileSessionManager
import app.loopdo.desktop.store.FileAnchorStore
import app.loopdo.desktop.sync.DesktopSync
import app.loopdo.desktop.time.LinuxDeviceClocks
import app.loopdo.shared.day.dateResolver
import app.loopdo.shared.day.settingsAt
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** The laptop's dependency graph: the phone's AppGraph with Linux clocks and file-backed stores (D2). */
class DesktopGraph(val dataDir: File) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> logE("loopdo", "background job failed", e) })
    val supabase = createSupabaseClient(AppConfig.supabaseUrl, AppConfig.publishableKey) {
        install(Auth) { sessionManager = FileSessionManager(File(dataDir, "session.json")) }
    }
    val auth = AuthRepository(SupabaseAuthBackend(supabase, scope), scope)
    val clocks = LinuxDeviceClocks()
    val anchorStore = FileAnchorStore(File(dataDir, "anchor.json"))
    val trustedClock = TrustedClock(clocks, anchorStore)
    val evidence = EvidenceFactory(clocks, anchorStore)
    private val http = HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 15_000 } }
    private val token: suspend () -> String? = { auth.freshToken() }
    val sync = DesktopSync(dataDir, auth, scope, AnchorRefresher(KtorTimeApi(http, token), anchorStore, clocks), { trustedClock.now().instant }, http = http)
    val todayData = TodayData(sync.db)
    private val userId = { (auth.session.value as? AuthState.SignedIn)?.userId }
    val journalActions = JournalActions(
        sync.db, trustedClock,
        dateAt = { t -> dateResolver(todayData.versionsNow())(t) },
        settingsAt = { t -> settingsAt(todayData.versionsNow(), t) },
        userId = userId,
    )
    val taskActions = TaskActions(sync.db, userId = userId, dateAt = { t -> dateResolver(todayData.versionsNow())(t) }, clock = trustedClock, evidence = evidence)
    val actions = Actions(
        sync.db, trustedClock, evidence,
        dateAt = { t -> dateResolver(todayData.versionsNow())(t) },
        userId = userId,
        series = { draft -> taskActions.saveTask(null, draft) },
        journal = journalActions,
        debug = false,
    )

    val habitActions = app.loopdo.client.habits.HabitActions(sync.db, trustedClock, evidence, dateAt = { t -> dateResolver(todayData.versionsNow())(t) }, userId = userId)
    val onboarding = app.loopdo.client.onboarding.Onboarding(
        app.loopdo.client.onboarding.KtorSettingsApi(http, token),
        app.loopdo.client.onboarding.PowerSyncProfileWriter(sync.db, userId),
    )
    private val photoApi = app.loopdo.client.journal.KtorPhotoApi(http, token)
    val photoCache = app.loopdo.client.journal.PhotoCache(File(dataDir, "photos"), photoApi)
    val photoUploader = app.loopdo.client.journal.PhotoUploader(
        photoApi,
        pending = {
            sync.db.getAll("SELECT f.id, f.path, p.entry_id, p.bytes FROM photo_files f JOIN journal_photos p ON p.id = f.id WHERE f.uploaded = 0") {
                app.loopdo.client.journal.PendingPhoto(java.util.UUID.fromString(it.getString(0)!!), it.getString(1)!!, it.getString(2)!!, it.getLong(3) ?: 0L)
            }
        },
        onError = { app.loopdo.client.log.logI("loopdo_photos", "upload later: ${it.message}") },
    ) { id -> sync.db.execute("UPDATE photo_files SET uploaded = 1 WHERE id = ?", listOf(id.toString())) }
    /** The phone's PhotoWorker: uploads after each add, every 5 min, and retries while photos wait (offline). */
    val photoLoop = app.loopdo.desktop.journal.PhotoLoop(scope, run = { photoUploader.runOnce() }, pending = { sync.db.get(app.loopdo.client.sync.PENDING_PHOTOS_SQL) { it.getLong(0) ?: 0L } })
    /** Where the laptop keeps photos it compressed itself (and downloads, by id). */
    val photoDir = File(dataDir, "photos")
    val drafts = app.loopdo.client.journal.LineDrafts(app.loopdo.desktop.store.FilePopupDrafts(File(dataDir, "drafts.json")))

    fun journalViewModel() = app.loopdo.client.journal.JournalViewModel(
        todayData.rows(), app.loopdo.client.journal.JournalData(sync.db), journalActions, { trustedClock.now().instant },
        photoFile = { id, path -> photoCache.file(id, path) }, uploadSoon = { photoLoop.kick() }, drafts = drafts,
    )

    fun todayViewModel() = TodayViewModel(todayData.rows(), actions, sync.notices) { trustedClock.now().instant }
}
