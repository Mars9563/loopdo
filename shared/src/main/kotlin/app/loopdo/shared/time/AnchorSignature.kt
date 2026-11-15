package app.loopdo.shared.time

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * The server signs every server time it hands out, bound to the user. A tick's
 * anchor must carry that signature, so a client can't invent an anchor time.
 * (It can't stop a modified app from lying about elapsed time; see the trust
 * model in the roadmap.)
 */

private fun mac(secret: ByteArray, userId: String, serverTime: Instant): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal("$userId|${serverTime.toEpochMilli()}".toByteArray(Charsets.UTF_8))
    }

fun signAnchor(secret: ByteArray, userId: String, serverTime: Instant): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(mac(secret, userId, serverTime))

fun verifyAnchor(secret: ByteArray, userId: String, serverTime: Instant, sig: String): Boolean {
    val given = try {
        Base64.getUrlDecoder().decode(sig)
    } catch (_: IllegalArgumentException) {
        return false
    }
    return MessageDigest.isEqual(given, mac(secret, userId, serverTime)) // constant-time
}
