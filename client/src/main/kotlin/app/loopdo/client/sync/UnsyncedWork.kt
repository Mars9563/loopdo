package app.loopdo.client.sync

/** Queued uploads, drafts the server hasn't got, or photos not yet in R2 (M6): an account switch must not wipe them. */
suspend fun hasUnsyncedWork(db: com.powersync.PowerSyncDatabase): Boolean =
    db.getNextCrudTransaction() != null ||
        db.get("SELECT count(*) FROM drafts") { it.getLong(0)!! } > 0 ||
        db.get(PENDING_PHOTOS_SQL) { it.getLong(0)!! } > 0

/** Photos still waiting for R2: queued files whose photo row exists (an orphan entry would retry forever). */
const val PENDING_PHOTOS_SQL = "SELECT count(*) FROM photo_files f JOIN journal_photos p ON p.id = f.id WHERE f.uploaded = 0"
