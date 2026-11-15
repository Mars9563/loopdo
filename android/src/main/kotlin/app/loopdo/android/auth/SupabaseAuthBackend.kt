package app.loopdo.android.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/** Supabase Auth via supabase-kt. The session is saved and restored by supabase-kt itself. */
class SupabaseAuthBackend(supabase: SupabaseClient, scope: CoroutineScope) : AuthBackend {
    private val auth = supabase.auth

    override val status: StateFlow<BackendStatus> = auth.sessionStatus
        .map { s ->
            when (s) {
                is SessionStatus.Authenticated -> {
                    val id = s.session.user?.id
                    if (id == null) BackendStatus.SignedOut else BackendStatus.SignedIn(UUID.fromString(id), s.session.accessToken)
                }
                SessionStatus.Initializing -> BackendStatus.Loading
                else -> BackendStatus.SignedOut
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, BackendStatus.Loading)

    override suspend fun signInWithPassword(email: String, password: String) = call {
        auth.signInWith(Email) { this.email = email; this.password = password }
    }

    override suspend fun signUp(email: String, password: String) = call {
        auth.signUpWith(Email) { this.email = email; this.password = password }
        Unit
    }

    override suspend fun verifySignupCode(email: String, code: String) = call {
        auth.verifyEmailOtp(OtpType.Email.SIGNUP, email = email, token = code)
        Unit
    }

    override suspend fun resendSignupCode(email: String) = call {
        auth.resendEmail(OtpType.Email.SIGNUP, email, captchaToken = null, redirectUrl = null)
    }

    override suspend fun sendPasswordReset(email: String) = call { auth.resetPasswordForEmail(email, redirectUrl = null) }

    override suspend fun resetPasswordWithCode(email: String, code: String, newPassword: String) = call {
        auth.verifyEmailOtp(OtpType.Email.RECOVERY, email = email, token = code)
        auth.updateUser { password = newPassword }
        Unit
    }

    override suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String) = call {
        auth.signInWith(IDToken) { this.idToken = idToken; provider = Google; nonce = rawNonce }
    }

    override suspend fun signOut() = call { auth.signOut() }

    /** Server errors → BackendError with Supabase's error code; network failures stay IOExceptions. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: AuthRestException) {
        throw BackendError(e.errorCode?.value ?: e.error, e.statusCode, e.errorDescription)
    } catch (e: RestException) {
        throw BackendError(null, e.statusCode, e.description ?: e.error)
    }
}
