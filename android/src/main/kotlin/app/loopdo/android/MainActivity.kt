package app.loopdo.android

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loopdo.android.auth.AuthFlow
import app.loopdo.android.today.TodayScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import app.loopdo.android.onboarding.FirstSyncScreen
import app.loopdo.android.onboarding.OnboardingScreen
import app.loopdo.android.onboarding.StartState
import app.loopdo.android.onboarding.hasDaySettings
import app.loopdo.android.onboarding.isOnboarded
import app.loopdo.android.onboarding.localRowCount
import app.loopdo.android.onboarding.startState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import app.loopdo.android.ui.nav.LoopDoNavBar
import app.loopdo.android.ui.nav.Tab
import app.loopdo.android.ui.placeholder.ComingSoon
import app.loopdo.android.ui.theme.Bracket
import app.loopdo.android.ui.theme.BracketItem
import app.loopdo.android.ui.theme.BracketRow
import app.loopdo.android.ui.theme.LoopDoColors
import app.loopdo.android.ui.theme.LoopDoTheme
import app.loopdo.android.ui.theme.LoopDoType
import kotlinx.coroutines.launch

class MainActivity : androidx.fragment.app.FragmentActivity() {
    companion object {
        /** Open on a tab ("journal" from the journal pop-up's [open app ↗]). */
        const val EXTRA_TAB = "tab"
        /** Open the habits tab on one habit's detail (its id). */
        const val EXTRA_HABIT = "habit"
        /** Open one task in the task editor (its id; the widget's task names). */
        const val EXTRA_TASK = "task"
        /** With EXTRA_TAB "journal": open today's writer at once (the journal reminder's [write], M7). */
        const val EXTRA_WRITE = "write"
        /** With EXTRA_TAB "stats": open the weekly review (the Sunday ping's [review >], M8). */
        const val EXTRA_REVIEW = "review"
        private const val LOCK_AUTHENTICATORS =
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    /** Where a launch asked to go (tab, habit, task). Also set by onNewIntent: the app may already be open. */
    private val link = kotlinx.coroutines.flow.MutableStateFlow<Link?>(null)

    /** A link is handed to the signed-in app once: after the app lock it must not open the same writer again (M7 review). */
    fun linkHandedOver(l: Link) { link.compareAndSet(l, null) }

    /** Fingerprint or the phone's PIN/pattern (BiometricPrompt); success opens the app. */
    fun askToUnlock(graph: AppGraph) {
        // The phone can't ask any more (its screen lock was removed): switch the lock off rather than trap the
        // user, who might clear the app's data and lose what isn't uploaded (review of M6).
        if (!canLock()) return noWayToUnlock(graph)
        val prompt = androidx.biometric.BiometricPrompt(
            this, androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) = graph.appLock.unlocked()
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (code == androidx.biometric.BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || code == androidx.biometric.BiometricPrompt.ERROR_HW_NOT_PRESENT) noWayToUnlock(graph)
                }
            },
        )
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("unlock loopdo_")
                .setAllowedAuthenticators(LOCK_AUTHENTICATORS)
                .build(),
        )
    }

    private fun noWayToUnlock(graph: AppGraph) {
        graph.appLock.cannotAuthenticate()
        setRecentsScreenshotEnabled(true)
        android.widget.Toast.makeText(this, "the phone has no screen lock any more, so the app lock is off.", android.widget.Toast.LENGTH_LONG).show()
    }

    /** Whether this phone can ask for fingerprint or its screen lock (needed before the switch turns on). */
    fun canLock(): Boolean =
        androidx.biometric.BiometricManager.from(this).canAuthenticate(LOCK_AUTHENTICATORS) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

    /** M7: Android's "allow notifications?" prompt; afterwards the reminders are planned again. */
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        val graph = (application as LoopDoApp).graph
        graph.scope.launch { runCatching { graph.reminders.replan() } }
    }

    /** Asks for notifications once ever (M7, spec §5.2); later only the [⚙] row offers Android's settings. */
    fun askNotificationsOnce(): Boolean {
        val prefs = getSharedPreferences("reminders-ui", MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false) || notificationsAllowed()) return false
        prefs.edit().putBoolean("notif_asked", true).apply()
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        return true
    }

    fun notificationsAllowed(): Boolean =
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()

    fun openNotificationSettings() = startActivity(
        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
    )

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        link.value = linkOf(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val graph = (application as LoopDoApp).graph
        if (savedInstanceState == null) link.value = linkOf(intent)
        // With the app lock on, the recents screen shows no picture of the journal (review of M6).
        setRecentsScreenshotEnabled(!graph.appLock.enabled)
        val demo = BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
        // Debug builds only: `am start ... --ez pinWidget true` asks the launcher to add the widget (the owner confirms).
        if (BuildConfig.DEBUG && intent.getBooleanExtra("pinWidget", false)) {
            getSystemService(android.appwidget.AppWidgetManager::class.java).requestPinAppWidget(
                android.content.ComponentName(this, app.loopdo.android.widget.LoopDoWidgetReceiver::class.java), null, null,
            )
        }
        setContent {
            LoopDoTheme {
                val session by graph.auth.session.collectAsStateWithLifecycle()
                val hasSynced by remember { graph.sync.db.currentStatus.asFlow().map { it.hasSynced } }.collectAsStateWithLifecycle(null)
                val hasSettings by remember { graph.sync.db.hasDaySettings() }.collectAsStateWithLifecycle(false)
                val onboarded by remember { graph.sync.db.isOnboarded() }.collectAsStateWithLifecycle(false)
                val rows by remember { graph.sync.db.localRowCount() }.collectAsStateWithLifecycle(0L)
                if (demo) {
                    DemoApp()
                    return@LoopDoTheme
                }
                val blocked by graph.sync.blocked.collectAsStateWithLifecycle()
                blocked?.let { message ->
                    SwitchRefused(message) { graph.scope.launch { graph.auth.signOut() } }
                    return@LoopDoTheme
                }
                when (startState(session, hasSynced, hasSettings, onboarded)) {
                    StartState.Loading -> Unit // the tinted wallpaper for a moment while the saved session loads
                    StartState.SignedOut -> AuthFlow(graph.auth, graph.google)
                    StartState.SyncingFirstTime -> FirstSyncScreen(rows)
                    StartState.NeedsOnboarding -> OnboardingScreen(graph.onboarding, settingsAlreadySaved = hasSettings)
                    StartState.Ready -> {
                        val l by link.collectAsStateWithLifecycle()
                        val locked by graph.appLock.locked.collectAsStateWithLifecycle()
                        // M6: the optional app lock covers the whole signed-in app until fingerprint / PIN.
                        if (locked) app.loopdo.android.lock.LockScreen { askToUnlock(graph) } else SignedInApp(graph, l, this@MainActivity)
                    }
                }
            }
        }
    }
}

/** A launch's destination; [nonce] makes the same link work again later. */
/** [write]: open today's journal writer (the journal reminder's [write], M7). */
data class Link(val tab: Tab?, val habit: String?, val task: String?, val write: Boolean = false, val review: Boolean = false, val nonce: Long = System.nanoTime())

private fun linkOf(intent: android.content.Intent?): Link? = intent?.let {
    linkFrom(
        it.getStringExtra(MainActivity.EXTRA_TAB), it.getStringExtra(MainActivity.EXTRA_HABIT), it.getStringExtra(MainActivity.EXTRA_TASK),
        it.getBooleanExtra(MainActivity.EXTRA_WRITE, false), it.getBooleanExtra(MainActivity.EXTRA_REVIEW, false),
    )
}

/** Where a launch goes; [write] only means something on the journal tab. */
internal fun linkFrom(tabName: String?, habit: String?, task: String?, write: Boolean, review: Boolean = false): Link? {
    val tab = when {
        habit != null -> Tab.HABITS
        task != null -> Tab.TODAY
        else -> Tab.entries.firstOrNull { it.name.equals(tabName, ignoreCase = true) }
    }
    return if (tab == null) null else Link(tab, habit, task, write = write && tab == Tab.JOURNAL, review = review && tab == Tab.STATS)
}

@Composable
private fun SignedInApp(graph: AppGraph, link: Link?, activity: MainActivity) {
    var tab by rememberSaveable { mutableStateOf(link?.tab ?: Tab.TODAY) }
    // A link is handed to its tab once; the tab clears it when it has opened it, so switching tabs
    // and coming back doesn't open the same task or habit again.
    var pending by remember { mutableStateOf<Link?>(null) }
    androidx.compose.runtime.LaunchedEffect(link) { link?.let { tab = it.tab ?: tab; pending = it; activity.linkHandedOver(it) } }
    val todayVm = viewModel { graph.todayViewModel() }
    val habitsVm = viewModel { graph.habitsViewModel() }
    val tasksVm = viewModel { graph.tasksViewModel() }
    val journalVm = viewModel { graph.journalViewModel() }
    val statsVm = viewModel { graph.statsViewModel() }
    var lockOn by remember { mutableStateOf(graph.appLock.enabled) }
    val toggleLock = {
        when {
            lockOn -> { graph.appLock.setEnabled(false); lockOn = false; activity.setRecentsScreenshotEnabled(true); todayVm.showMessage("app lock off.") }
            activity.canLock() -> { graph.appLock.setEnabled(true); lockOn = true; activity.setRecentsScreenshotEnabled(false); todayVm.showMessage("app lock on: it asks after a minute away.") }
            else -> todayVm.showMessage("set a screen lock or fingerprint on the phone first.")
        }
    }
    // M7: notifications are asked for once, with one line first; if refused, [⚙] shows a row to Android's settings.
    var notificationsOff by remember { mutableStateOf(!activity.notificationsAllowed()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notificationsOff = !activity.notificationsAllowed()
        onPauseOrDispose { }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (activity.askNotificationsOnce()) todayVm.showMessage("loopdo_ reminds you of habits, tasks and the journal.")
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxSize().statusBarsPadding()) {
            when (tab) {
                Tab.TODAY -> TodayScreen(todayVm, tasksVm, pending?.task?.let { app.loopdo.android.today.OpenTask(it, pending!!.nonce) }, onLinkHandled = { pending = null }, onOpenHabit = { id -> pending = Link(Tab.HABITS, id, null); tab = Tab.HABITS }, lockOn = lockOn, onToggleLock = toggleLock, notificationsOff = notificationsOff, onAllowNotifications = { activity.openNotificationSettings() }) {
                    graph.scope.launch { graph.auth.signOut().onFailure { todayVm.showMessage(it.message ?: "couldn't sign out. try again.") } }
                }
                Tab.JOURNAL -> app.loopdo.android.journal.JournalTab(journalVm, openWriter = pending?.write == true) { pending = null }
                Tab.HABITS -> app.loopdo.android.habits.HabitsTab(habitsVm, pending?.habit, pending?.nonce ?: 0L) { pending = null }
                Tab.STATS -> app.loopdo.android.stats.StatsTab(statsVm, openReview = pending?.review == true) { pending = null }
            }
        }
        LoopDoNavBar(selected = tab, onSelect = { tab = it })
    }
}

/** Debug-only look check: the mockup's sample day, no data access. */
@Composable
private fun DemoApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxSize().statusBarsPadding()) {
            if (tab == Tab.TODAY) app.loopdo.android.today.TodayContent(
                remember { app.loopdo.android.today.demoToday() }, null, app.loopdo.android.today.DemoUi, onSignOut = {},
            ) else ComingSoon(tab)
        }
        LoopDoNavBar(selected = tab, onSelect = { tab = it })
    }
}

/**
 * A different account signed in while the previous one still has changes the server hasn't got.
 * Nothing of the previous account is shown or uploaded under the new one; it waits until that
 * account signs in again and syncs (review finding I1). Not mocked: a plain error state.
 */
@Composable
private fun SwitchRefused(message: String, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        Text("~/sync", style = LoopDoType.title.copy(color = LoopDoColors.accent), modifier = Modifier.padding(vertical = 12.dp))
        Text(message, style = LoopDoType.body.copy(color = LoopDoColors.warn), modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
        BracketRow(BracketItem("sign out", primary = true, onClick = onSignOut))
    }
}
