package app.loopdo.android.auth

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

sealed interface BackendStatus {
    data object Loading : BackendStatus
    data object SignedOut : BackendStatus
    data class SignedIn(val userId: UUID, val accessToken: String) : BackendStatus
    /**
     * The token could not be refreshed (offline, or the auth server is down). The stored
     * session is still on the phone ([storedAccessToken]) and the library keeps retrying.
     */
    data class RefreshFailed(val storedAccessToken: String?) : BackendStatus
}

/** An auth server error: Supabase's error code (e.g. invalid_credentials) and HTTP status. */
class BackendError(val code: String?, val status: Int?, message: String) : Exception(message)

/** What the app needs from Supabase Auth; the real one wraps supabase-kt, tests use a fake. */
interface AuthBackend {
    val status: StateFlow<BackendStatus>
    suspend fun signInWithPassword(email: String, password: String)
    /** Creates the account and emails a 6-digit code; not signed in yet. */
    suspend fun signUp(email: String, password: String)
    suspend fun verifySignupCode(email: String, code: String)
    suspend fun resendSignupCode(email: String)
    suspend fun sendPasswordReset(email: String)
    suspend fun resetPasswordWithCode(email: String, code: String, newPassword: String)
    suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String)
    suspend fun signOut()
    /** Refreshes the session now; returns the new access token (null if there is no session). */
    suspend fun refreshSession(): String?
}
