package app.loopdo.android

import android.app.Application
import app.loopdo.android.auth.AuthRepository
import app.loopdo.android.auth.GoogleSignIn
import app.loopdo.android.auth.SupabaseAuthBackend
import app.loopdo.android.config.AppConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency graph: one of each, alive for the app's lifetime. */
class AppGraph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val supabase = createSupabaseClient(AppConfig.supabaseUrl, AppConfig.publishableKey) { install(Auth) }
    val auth = AuthRepository(SupabaseAuthBackend(supabase, scope), scope)
    val google = GoogleSignIn(AppConfig.googleWebClientId)
}

class LoopDoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph()
    }
}
