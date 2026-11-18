package app.loopdo.android.time

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.loopdo.shared.time.TimeAnchor
import java.time.DateTimeException
import java.time.Instant

// The phone's side of the clock check (D1 split): Android timers and the anchor kept in SharedPreferences.

class AndroidDeviceClocks(private val context: Context) : DeviceClocks {
    override fun wallClock(): Instant = Instant.now()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    override fun networkTime(): Instant? = try {
        SystemClock.currentNetworkTimeClock().instant()
    } catch (e: DateTimeException) {
        null // no network time known yet (e.g. offline right after a reboot)
    }
}

class PrefsAnchorStore(context: Context) : AnchorStore {
    private val prefs = context.getSharedPreferences("loopdo_anchor", Context.MODE_PRIVATE)

    override fun current(): SignedAnchor? {
        val t = prefs.getString("server_time", null) ?: return null
        return SignedAnchor(
            TimeAnchor(Instant.parse(t), prefs.getLong("elapsed", 0), prefs.getInt("boot", -1)),
            prefs.getString("sig", "")!!,
        )
    }

    override fun save(a: SignedAnchor) {
        prefs.edit()
            .putString("server_time", a.anchor.serverTime.toString())
            .putLong("elapsed", a.anchor.elapsedRealtimeMs)
            .putInt("boot", a.anchor.bootCount)
            .putString("sig", a.sig)
            .apply()
    }
}

