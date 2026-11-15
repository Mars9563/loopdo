package app.loopdo.server

import com.auth0.jwk.JwkProvider
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.jwt
import java.util.UUID

/** The signed-in user, from a valid Supabase access token. */
data class UserPrincipal(val id: UUID)

const val AUTH = "supabase"

fun Application.installAuth(jwkProvider: JwkProvider, issuer: String) {
    authentication {
        jwt(AUTH) {
            verifier(jwkProvider, issuer) {
                withAudience("authenticated")
                acceptLeeway(30)
            }
            validate { cred ->
                cred.payload.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(::UserPrincipal)
            }
        }
    }
}
