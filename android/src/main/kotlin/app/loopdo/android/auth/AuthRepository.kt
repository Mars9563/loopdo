package app.loopdo.android.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.UUID

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: UUID, val accessToken: String) : AuthState
}

/** A problem worded for the person, as in the locked sign-in mockup. */
class AuthProblem(message: String) : Exception(message)

class AuthRepository(
    private val backend: AuthBackend,
    scope: CoroutineScope,
    private val clock: () -> java.time.Instant = java.time.Instant::now,
) {
    /**
     * Once signed in, a later "loading" is not a sign-out: supabase-kt's Android lifecycle hook
     * reports "Initializing" whenever the app goes to the background. Only a real sign-out (or a
     * refresh failure without a stored session) leaves SignedIn.
     */
    val session: StateFlow<AuthState> = backend.status
        .map { it.toState() }
        .scan(backend.status.value.toState()) { prev, next -> if (next is AuthState.Loading && prev is AuthState.SignedIn) prev else next }
        .stateIn(scope, SharingStarted.Eagerly, backend.status.value.toState())

    suspend fun signIn(email: String, password: String) = attempt { backend.signInWithPassword(email.trim(), password) }
    suspend fun signUp(email: String, password: String) = attempt { backend.signUp(email.trim(), password) }
    suspend fun verifyCode(email: String, code: String) = attempt { backend.verifySignupCode(email.trim(), code.trim()) }
    suspend fun resendCode(email: String) = attempt { backend.resendSignupCode(email.trim()) }
    suspend fun sendReset(email: String) = attempt { backend.sendPasswordReset(email.trim()) }
    suspend fun resetWithCode(email: String, code: String, newPassword: String) =
        attempt { backend.resetPasswordWithCode(email.trim(), code.trim(), newPassword) }
    suspend fun signInWithGoogle(idToken: String, rawNonce: String) = attempt { backend.signInWithGoogleIdToken(idToken, rawNonce) }
    /**
     * The access token to send, refreshed first if it expires within a minute. With supabase-kt's
     * lifecycle hook off, a night in deep sleep can leave an expired token that the library still
     * calls valid (final review I2). Offline, the stored token is returned: the server's 401 makes
     * the upload retry later, and nothing is lost.
     */
    suspend fun freshToken(): String? {
        val s = session.value as? AuthState.SignedIn ?: return null
        val exp = expiryOf(s.accessToken)
        if (exp == null || exp.isAfter(clock().plusSeconds(60))) return s.accessToken
        return runCatching { backend.refreshSession() }.getOrNull() ?: s.accessToken
    }

    /** Signing out needs the network (Supabase revokes the session); offline it is refused, never a crash. */
    suspend fun signOut(): Result<Unit> = try {
        backend.signOut(); Result.success(Unit)
    } catch (e: BackendError) {
        Result.failure(AuthProblem(readable(e)))
    } catch (e: IOException) {
        Result.failure(AuthProblem("no connection. signing out needs the network."))
    }

    private suspend fun attempt(block: suspend () -> Unit): Result<Unit> = try {
        block(); Result.success(Unit)
    } catch (e: BackendError) {
        Result.failure(AuthProblem(readable(e)))
    } catch (e: IOException) {
        Result.failure(AuthProblem("no connection. try again when you're online."))
    }

    private fun BackendStatus.toState(): AuthState = when (this) {
        BackendStatus.Loading -> AuthState.Loading
        BackendStatus.SignedOut -> AuthState.SignedOut
        is BackendStatus.SignedIn -> AuthState.SignedIn(userId, accessToken)
        // Keep working offline with the stored session; uploads get 401 → retry until a refresh succeeds.
        is BackendStatus.RefreshFailed -> storedAccessToken?.let { t -> userIdFromJwt(t)?.let { AuthState.SignedIn(it, t) } } ?: AuthState.SignedOut
    }

    companion object {
        /** The `exp` claim of an access token, or null if it can't be read. */
        fun expiryOf(token: String): java.time.Instant? = runCatching {
            val payload = String(java.util.Base64.getUrlDecoder().decode(token.split('.')[1]))
            java.time.Instant.ofEpochSecond(kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject["exp"]!!.jsonPrimitive.content.toLong())
        }.getOrNull()

        /** The `sub` claim of an access token (the signature is the server's business, not ours). */
        fun userIdFromJwt(token: String): UUID? = runCatching {
            val payload = String(java.util.Base64.getUrlDecoder().decode(token.split('.')[1]))
            val sub = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject["sub"]!!.jsonPrimitive.content
            UUID.fromString(sub)
        }.getOrNull()

        /** Supabase error codes → the app's plain words. */
        fun readable(e: BackendError): String = when {
            e.code == "invalid_credentials" -> "wrong email or password. try again or [reset]."
            e.code == "email_not_confirmed" -> "confirm your email first: enter the code we sent."
            e.code == "otp_expired" -> "that code is wrong or expired. check it, or tap [resend]."
            e.code == "user_already_exists" || e.code == "email_exists" -> "an account with this email exists. sign in instead."
            e.code == "weak_password" -> "that password is too weak: use at least 8 characters."
            e.code == "over_email_send_rate_limit" || e.code == "over_request_rate_limit" -> "too many attempts. wait a minute and try again."
            e.code == "signup_disabled" || e.message?.contains("Sign-ups are closed", ignoreCase = true) == true -> "sign-ups are closed."
            e.code == "validation_failed" || e.code == "email_address_invalid" -> "that email address doesn't look right."
            else -> "something went wrong (${e.code ?: e.status ?: "?"}). try again."
        }
    }
}
