package app.loopdo.android.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID

/**
 * Android's own Google account picker (Credential Manager). The ID token carries a
 * hashed nonce; Supabase gets the raw nonce and checks they match, so a stolen token
 * can't be replayed.
 */
class GoogleSignIn(private val webClientId: String) {
    /** Must be called with an Activity context. Returns (idToken, rawNonce); null if the person cancelled. */
    suspend fun idToken(activity: Context): Result<Pair<String, String>?> = runCatching {
        val rawNonce = UUID.randomUUID().toString()
        val hashed = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray()).joinToString("") { "%02x".format(it) }
        val option = GetSignInWithGoogleOption.Builder(webClientId).setNonce(hashed).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        try {
            val result = CredentialManager.create(activity).getCredential(activity, request)
            GoogleIdTokenCredential.createFrom(result.credential.data).idToken to rawNonce
        } catch (e: GetCredentialCancellationException) {
            null
        }
    }
}
