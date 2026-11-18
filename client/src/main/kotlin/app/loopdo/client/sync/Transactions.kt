package app.loopdo.client.sync

import com.powersync.PowerSyncDatabase
import com.powersync.PowerSyncException
import com.powersync.db.internal.PowerSyncTransaction

/**
 * One local transaction for one user action (API contract: it uploads as one batch and a 422
 * undoes exactly it). PowerSync wraps whatever the block throws; a rule's own refusal
 * (IllegalArgument/IllegalState, with its plain message for the screen) is thrown again as it was.
 */
suspend fun <R> PowerSyncDatabase.oneTransaction(block: (PowerSyncTransaction) -> R): R = try {
    writeTransaction { block(it) }
} catch (e: PowerSyncException) {
    throw (e.cause as? IllegalStateException) ?: (e.cause as? IllegalArgumentException) ?: e
}
