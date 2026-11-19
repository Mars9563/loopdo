package app.loopdo.shared.remind

import app.loopdo.shared.task.uuidV5
import java.util.UUID

// D7: the laptop takes over pings while it is in use. One `device_presence` row per laptop; the server checks the id
// is this one (so a device can only ever write its own row) and the phone reads `active_until` at ping time.

/** A laptop's presence row id: the same on the laptop and the server. */
fun presenceId(user: UUID, deviceId: UUID): UUID = uuidV5(user, "presence:$deviceId")

/**
 * Whether a laptop is in use right now: any presence row whose active_until is after [now] (the phone's trusted
 * clock). A stale row (a laptop that crashed or went offline) stops counting by itself within 5 min.
 */
fun laptopInUse(activeUntil: List<java.time.Instant>, now: java.time.Instant): Boolean = activeUntil.any { it > now }
