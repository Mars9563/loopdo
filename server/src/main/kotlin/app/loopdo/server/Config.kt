package app.loopdo.server

data class R2Config(val accountId: String, val bucket: String, val accessKeyId: String, val secretAccessKey: String)

/**
 * Everything comes from the environment (k8s Secret `loopdo-api-env`).
 * A missing key fails startup and names the key, never a value.
 */
data class Config(
    val supabaseUrl: String,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val anchorSecret: ByteArray,
    /** Optional: the secret before the last rotation, still accepted for queued anchors. */
    val previousAnchorSecret: ByteArray?,
    val r2: R2Config,
    val port: Int,
) {
    val issuer get() = "${supabaseUrl.trimEnd('/')}/auth/v1"
    val jwksUrl get() = "$issuer/.well-known/jwks.json"

    override fun toString() = "Config(supabaseUrl=$supabaseUrl, dbUser=$dbUser, r2.bucket=${r2.bucket}, port=$port)"

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): Config {
            fun req(key: String) = env(key)?.takeIf { it.isNotBlank() } ?: error("missing environment variable $key")
            val anchor = req("LOOPDO_ANCHOR_SECRET")
            require(anchor.length >= 32) { "LOOPDO_ANCHOR_SECRET must be at least 32 characters" }
            return Config(
                supabaseUrl = req("LOOPDO_SUPABASE_URL"),
                dbUrl = req("LOOPDO_DB_URL"),
                dbUser = req("LOOPDO_DB_USER"),
                dbPassword = req("LOOPDO_DB_PASSWORD"),
                anchorSecret = anchor.toByteArray(),
                previousAnchorSecret = env("LOOPDO_ANCHOR_SECRET_PREVIOUS")?.takeIf { it.isNotBlank() }?.toByteArray(),
                r2 = R2Config(req("R2_ACCOUNT_ID"), req("R2_BUCKET"), req("R2_ACCESS_KEY_ID"), req("R2_SECRET_ACCESS_KEY")),
                port = env("PORT")?.toIntOrNull() ?: 8080,
            )
        }
    }
}
