package app.loopdo.android.config

import app.loopdo.android.BuildConfig

/** Public client configuration (no secrets). */
object AppConfig {
    const val supabaseUrl = BuildConfig.SUPABASE_URL
    const val publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    const val powerSyncUrl = BuildConfig.POWERSYNC_URL
    const val apiUrl = BuildConfig.API_URL
    const val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
}
