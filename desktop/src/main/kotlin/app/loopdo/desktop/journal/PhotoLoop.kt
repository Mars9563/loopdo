package app.loopdo.desktop.journal

import app.loopdo.client.log.logI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * The phone's PhotoWorker for the laptop: uploads queued photos to R2 right after one is added ([kick]), every
 * [every] ms, and while some are still waiting (offline), again after [firstRetry] ms, doubling up to [every].
 */
class PhotoLoop(
    scope: CoroutineScope,
    private val run: suspend () -> Int,
    private val pending: suspend () -> Long,
    private val every: Long = 300_000,
    private val firstRetry: Long = 30_000,
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    fun kick() { kicks.trySend(Unit) }

    init {
        scope.launch {
            var wait = every
            while (true) {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                select { kicks.onReceive { }; onTimeout(wait) { } }
                runCatching { run() }.onFailure { logI("loopdo_photos", "upload later: ${it.message}") }
                wait = if (runCatching { pending() }.getOrDefault(0L) > 0) (if (wait >= every) firstRetry else minOf(wait * 2, every)) else every
            }
        }
    }
}
