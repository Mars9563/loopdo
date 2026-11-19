package app.loopdo.android.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

// Owner, 2026-11-19: like other apps that must work in the background, loopdo_ asks once for "Unrestricted" battery.
// Optimised, Android cuts its network with the screen off, so the phone stops hearing that the laptop is in use (D7),
// and Samsung may delay its alarms. The [⚙] row shows where it stands and offers the same dialog again.

/** Ask now? Only once ever, only while still optimised, and never on top of the notifications ask. */
fun shouldAskBattery(unrestricted: Boolean, askedBefore: Boolean, otherAskShowing: Boolean): Boolean =
    !unrestricted && !askedBefore && !otherAskShowing

/** The [⚙] row's state. */
fun batteryLine(unrestricted: Boolean): String = if (unrestricted) "unrestricted ✓" else "optimised"

fun batteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** Android's own "Let loopdo_ always run in the background?" dialog. */
fun batteryAskIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
