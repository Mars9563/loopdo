package app.loopdo.desktop.lock

import app.loopdo.desktop.store.FileKeyValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// Owner, 2026-11-19 (desktop-pin-v1): the laptop's light lock. Off by default; a 4-digit pin asked when the window is
// opened more than an hour after the last unlock. Tray, pings and quick add are never locked. Stored as a salted
// PBKDF2 hash, never the digits; the wrong-try wait is stored too, so killing loopdo_ doesn't reset it.

sealed interface Unlock {
    data object Ok : Unlock
    data class Wrong(val left: Int) : Unlock
    data class Wait(val until: Instant) : Unlock
}

class PinLock(private val kv: FileKeyValue, private val now: () -> Instant = Instant::now) {
    private val _needed = MutableStateFlow(false)
    /** True while the window must show ~/locked instead of the app. */
    val needed: StateFlow<Boolean> = _needed

    val isSet: Boolean get() = kv.getString(HASH) != null

    /** A new pin (4 digits). Setting it counts as an unlock. */
    fun set(pin: String) {
        require(pin.length == 4 && pin.all { it in '0'..'9' }) { "a pin is 4 digits." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        kv.putString(SALT, b64(salt))
        kv.putString(HASH, b64(hash(pin, salt, ITERATIONS)))
        kv.putLong(ITER, ITERATIONS.toLong())
        resetTries(); unlocked()
    }

    fun clear() {
        listOf(SALT, HASH, ITER, WRONG, WAIT_UNTIL, LAST_UNLOCK).forEach(kv::remove)
        _needed.value = false
    }

    /** The pin matches (for change and off in ~/settings). Doesn't count as a try. */
    fun check(pin: String): Boolean {
        val salt = kv.getString(SALT)?.let(::unb64) ?: return false
        val want = kv.getString(HASH)?.let(::unb64) ?: return false
        val iter = kv.getLong(ITER)?.toInt() ?: ITERATIONS
        return MessageDigest.isEqual(want, hash(pin, salt, iter))
    }

    /** The window was opened: locked if a pin is set and the last unlock was more than an hour ago. */
    fun windowOpened() {
        if (!isSet) { _needed.value = false; return }
        val last = kv.getLong(LAST_UNLOCK)?.let(Instant::ofEpochMilli)
        if (last == null || Duration.between(last, now()) > AWAY) _needed.value = true
    }

    fun tryUnlock(pin: String): Unlock {
        kv.getLong(WAIT_UNTIL)?.let(Instant::ofEpochMilli)?.takeIf { it > now() }?.let { return Unlock.Wait(it) }
        if (check(pin)) { resetTries(); unlocked(); return Unlock.Ok }
        val wrong = (kv.getLong(WRONG) ?: 0L) + 1
        if (wrong >= TRIES) {
            val until = now().plus(WAIT)
            kv.putLong(WAIT_UNTIL, until.toEpochMilli()); kv.putLong(WRONG, 0L)
            return Unlock.Wait(until)
        }
        kv.putLong(WRONG, wrong)
        return Unlock.Wrong(left = (TRIES - wrong).toInt())
    }

    private fun unlocked() { kv.putLong(LAST_UNLOCK, now().toEpochMilli()); _needed.value = false }
    private fun resetTries() { kv.remove(WRONG); kv.remove(WAIT_UNTIL) }

    private companion object {
        const val SALT = "pin_salt"; const val HASH = "pin_hash"; const val ITER = "pin_iterations"
        const val WRONG = "wrong_tries"; const val WAIT_UNTIL = "wait_until"; const val LAST_UNLOCK = "last_unlock"
        const val ITERATIONS = 120_000
        const val TRIES = 5L
        val AWAY: Duration = Duration.ofHours(1)
        val WAIT: Duration = Duration.ofSeconds(30)
        fun hash(pin: String, salt: ByteArray, iter: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, iter, 256)).encoded
        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}
