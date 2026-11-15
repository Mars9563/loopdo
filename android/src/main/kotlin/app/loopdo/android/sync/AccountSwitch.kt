package app.loopdo.android.sync

enum class SwitchAction { CONNECT, CLEAR_THEN_CONNECT, REFUSE }

/**
 * What to do when [newUser] signs in on a phone last used by [lastUser]. Another account's
 * data is cleared first, but never while it still has changes the server hasn't got
 * (queued uploads or drafts): those would be lost for good.
 */
fun accountSwitch(lastUser: String?, newUser: String, unsyncedChanges: Boolean): SwitchAction = when {
    lastUser == null || lastUser == newUser -> SwitchAction.CONNECT
    unsyncedChanges -> SwitchAction.REFUSE
    else -> SwitchAction.CLEAR_THEN_CONNECT
}
