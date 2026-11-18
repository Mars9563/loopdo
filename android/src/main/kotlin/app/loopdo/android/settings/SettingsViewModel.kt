package app.loopdo.android.settings

import app.loopdo.client.settings.AccountApi
import app.loopdo.client.settings.Deleting
import app.loopdo.client.settings.ExportResult
import app.loopdo.client.settings.adminRow
import app.loopdo.client.settings.deletingState
import app.loopdo.client.settings.exportResultLine
import app.loopdo.client.settings.inForceNow
import app.loopdo.client.settings.keepLabel
import app.loopdo.client.settings.pendingLine
import app.loopdo.client.settings.withDayEnd
import app.loopdo.client.settings.withPrefs
import app.loopdo.client.settings.withZone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.loopdo.client.notify.reminderPrefsFromSettings
import app.loopdo.client.onboarding.SettingsApi
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.remind.ReminderPrefs
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The profile row as the phone has it (synced). */
data class ProfileRow(val name: String, val settings: String?, val isAdmin: Boolean, val deleteRequestedAt: Instant?, val wipeClaimedAt: Instant?)

data class SettingsUi(
    val data: Pair<String, String?>, val waitingNow: Boolean,
    val dayEnd: LocalTime, val zone: ZoneId, val pending: String?, val keep: String?,
    val name: String, val prefs: ReminderPrefs, val admin: String?, val version: String,
)

/** ~/settings (locked settings-v1 A/B/D). Writes go to the synced profile, or to the API for day settings / account. */
class SettingsViewModel(
    profile: Flow<ProfileRow?>,
    versions: Flow<List<DaySettingsVersion>>,
    waiting: Flow<Waiting>,
    private val db: PowerSyncDatabase,
    private val daySettings: SettingsApi,
    private val account: AccountApi,
    private val export: suspend (OutputStream) -> ExportResult,
    private val now: () -> Instant,
    private val version: String,
) : ViewModel() {
    private val signups = MutableStateFlow<Boolean?>(null)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val latestProfile = profile.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val latestVersions = versions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** M9: while the account waits to be deleted, the app shows only the deleting screen (every phone). */
    val deleting: StateFlow<Deleting?> = combine(latestProfile, latestVersions) { p, v ->
        if (v.isEmpty()) null else deletingState(p?.deleteRequestedAt, p?.wipeClaimedAt, inForceNow(v, now()).zone)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<SettingsUi?> = combine(latestProfile, latestVersions, waiting, signups) { p, v, w, s ->
        if (v.isEmpty()) null else {
            val t = now()
            val cur = inForceNow(v, t)
            SettingsUi(
                dataLine(w, t, cur.zone), w.total > 0, cur.dayEnd, cur.zone, pendingLine(v, t), keepLabel(v, t),
                p?.name.orEmpty(), reminderPrefsFromSettings(p?.settings), adminRow(p?.isAdmin == true, s), version,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Ask again whenever the admin flag arrives (it may sync after the screen opened).
        viewModelScope.launch {
            latestProfile.map { it?.isAdmin == true }.distinctUntilChanged().collect { if (it) refreshAdmin() }
        }
    }

    /** The admin switch needs the server (app_config isn't synced): asked when the screen opens. */
    fun refreshAdmin() = viewModelScope.launch {
        if (latestProfile.value?.isAdmin == true) runCatching { signups.value = account.signups() }
            .onFailure { android.util.Log.w("loopdo_settings", "sign-up switch: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun act(block: suspend () -> String?) = viewModelScope.launch {
        _busy.value = true
        _message.value = try { block() } catch (e: Exception) { e.message ?: "that didn't work. try again." }
        _busy.value = false
    }

    /** Day settings go to the server (they wait for today to end there): offline, say so plainly. */
    private suspend fun online(block: suspend () -> String?): String? =
        try { block() } catch (e: java.io.IOException) { throw IllegalStateException("needs a connection. try again when you're online.") }

    fun setDayEnd(hour: Int) = act { online {
        val s = withDayEnd(latestVersions.value, now(), hour)
        daySettings.setDaySettings(s.zone.id, "%02d:00".format(hour)); "the new day end starts when today ends."
    } }
    fun setZone(zone: String) = act { online {
        val s = withZone(latestVersions.value, now(), zone)
        daySettings.setDaySettings(zone, "%02d:%02d".format(s.dayEnd.hour, s.dayEnd.minute)); "the new time zone starts when today ends."
    } }
    /** Cancel a waiting change: send the settings in force now again (it replaces the waiting one). */
    fun keepCurrent() = act { online {
        val v = latestVersions.value
        val cur = inForceNow(v, now())
        daySettings.setDaySettings(cur.zone.id, "%02d:%02d".format(cur.dayEnd.hour, cur.dayEnd.minute)); null
    } }
    fun setName(name: String) = act {
        val n = name.trim().take(60)
        require(n.isNotEmpty()) { "a name can't be empty." }
        db.execute("UPDATE profiles SET display_name = ?", listOf(n)); null
    }
    fun setPrefs(p: ReminderPrefs) = act {
        db.execute("UPDATE profiles SET settings = ?", listOf(withPrefs(latestProfile.value?.settings, p))); null
    }
    fun setSignups(open: Boolean) = act { account.setSignups(open); signups.value = open; null }
    /** [open] gives the file the owner picked in Android's "save as"; it is closed when the ZIP is written. */
    fun exportTo(open: () -> OutputStream?, discard: () -> Unit = {}) = act {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = open() ?: return@withContext "couldn't open that file."
            try {
                out.use { exportResultLine(export(it)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                discard(); throw e
            } catch (e: Exception) {
                discard(); "the export failed, nothing was saved. try again."
            }
        }
    }
    fun deleteAccount() = act { account.delete(); null }
    fun keepAccount() = act { account.keep(); null }
    fun dismiss() { _message.value = null }
}
