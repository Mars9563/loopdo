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
import kotlinx.coroutines.flow.map
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
    /** ONE store for unsaved text (the writer and quick add share it, like the phone's app and widget pop-up). */
    val popupDrafts = app.loopdo.desktop.store.FilePopupDrafts(File(dataDir, "drafts.json"))
    val drafts = app.loopdo.client.journal.LineDrafts(popupDrafts)

    fun popupViewModel() = app.loopdo.client.popup.PopupViewModel(actions, popupDrafts) {}
    suspend fun today(): java.time.LocalDate = dateResolver(todayData.versionsNow())(trustedClock.now().instant)
    /** "HH:mm" now in the account's LoopDo time zone (the stamp a line saved now gets). */
    suspend fun stampNow(): String { val now = trustedClock.now().instant
        return now.atZone(settingsAt(todayData.versionsNow(), now).zone).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }

    fun journalViewModel() = app.loopdo.client.journal.JournalViewModel(
        todayData.rows(), app.loopdo.client.journal.JournalData(sync.db), journalActions, { trustedClock.now().instant },
        photoFile = { id, path -> photoCache.file(id, path) }, uploadSoon = { photoLoop.kick() }, drafts = drafts,
    )

    val focusActions = app.loopdo.client.stats.FocusActions(
        sync.db, userId = { userId()?.toString() }, today = { dateResolver(todayData.versionsNow())(trustedClock.now().instant) }, evidence = evidence,
    )
    /** Laptop reminder state (D6 reads it): opening the review on a Sunday marks that Sunday as reviewed (phone parity). */
    val reminderKeys = app.loopdo.desktop.store.FileKeyValue(File(dataDir, "reminders.json"))

    fun statsViewModel() = app.loopdo.client.stats.StatsViewModel(app.loopdo.client.stats.StatsData(sync.db).rows(), focusActions, now = { trustedClock.now().instant }) { today ->
        app.loopdo.client.stats.sundayToSilence(today)?.let { reminderKeys.putLong("reviewed:$it", 1L) }
    }

    /** This week's promises, checked ("last week you said" on ~/stats), from the phone's buildReview for this week. */
    fun thisWeekPromises(): kotlinx.coroutines.flow.Flow<Pair<List<String>, Int>> =
        app.loopdo.client.stats.StatsData(sync.db).rows().map { r ->
            if (r.versions.isEmpty()) emptyList<String>() to 0
            else { val today = dateResolver(r.versions)(trustedClock.now().instant)
                app.loopdo.client.stats.buildReview(r, today, app.loopdo.shared.stats.weekStart(today)).let { it.lastWeekSaid to it.kept } }
        }

    fun tasksViewModel() = app.loopdo.client.tasks.TasksViewModel(app.loopdo.client.tasks.TaskData(sync.db), taskActions)

    fun habitsViewModel() = app.loopdo.client.habits.HabitsViewModel(todayData.rows(), habitActions) { trustedClock.now().instant }

    fun todayViewModel() = TodayViewModel(todayData.rows(), actions, sync.notices) { trustedClock.now().instant }
}
