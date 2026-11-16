package app.loopdo.android.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.loopdo.android.LoopDoApp
import app.loopdo.android.auth.AuthState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** True once [pending] reports nothing left (checked every [pollMs]); false after [timeoutMs]. */
suspend fun awaitUploaded(timeoutMs: Long, pollMs: Long, pending: suspend () -> Boolean): Boolean =
    withTimeoutOrNull(timeoutMs) {
        while (pending()) delay(pollMs)
        true
    } ?: false

/**
 * Background sync (owner, 2026-11-16). Android freezes the app while it sits unused, so without
 * this, ticks waited for the app or widget to be used again. Every ~15 minutes while online this
 * job wakes the app: the sync engine (already connected by SyncModule) uploads the queue and
 * downloads changes, which redraw the widget.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LoopDoApp).graph
        if (graph.auth.session.value !is AuthState.SignedIn) return Result.success()
        val done = awaitUploaded(timeoutMs = 60_000, pollMs = 1_000) { graph.sync.db.getNextCrudTransaction() != null }
        Log.i("loopdo_sync", "background sync: ${if (done) "queue empty" else "changes still queued (next run retries)"}")
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("loopdo-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
