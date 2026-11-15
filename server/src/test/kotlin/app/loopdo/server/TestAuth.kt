package app.loopdo.server

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** A fake Supabase: an ES256 key pair, its JWKS and a token minter. */
object TestAuth {
    const val ISSUER = "https://test-ref.supabase.co/auth/v1"
    private const val KID = "test-key"

    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pub = keyPair.public as ECPublicKey
    private val priv = keyPair.private as ECPrivateKey

    private fun b64(bytes: ByteArray): String {
        val unsigned = if (bytes.size > 32 && bytes[0] == 0.toByte()) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
        val padded = ByteArray(32 - unsigned.size) + unsigned
        return Base64.getUrlEncoder().withoutPadding().encodeToString(padded)
    }

    val jwkProvider = JwkProvider { kid ->
        require(kid == KID) { "unknown kid $kid" }
        Jwk.fromValues(mapOf(
            "kty" to "EC", "crv" to "P-256", "alg" to "ES256", "use" to "sig", "kid" to KID,
            "x" to b64(pub.w.affineX.toByteArray()), "y" to b64(pub.w.affineY.toByteArray()),
        ))
    }

    fun token(
        user: UUID,
        issuer: String = ISSUER,
        expiresAt: Instant = Instant.now().plusSeconds(3600),
        audience: String = "authenticated",
    ): String = JWT.create()
        .withKeyId(KID)
        .withIssuer(issuer)
        .withAudience(audience)
        .withSubject(user.toString())
        .withClaim("role", "authenticated")
        .withExpiresAt(Date.from(expiresAt))
        .sign(Algorithm.ECDSA256(pub, priv))
}
