package app.loopdo.android.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Review of M6, important 6: a photo not yet in R2 is unsynced work, so another account can't sign in and wipe it. */
@RunWith(AndroidJUnit4::class)
class UnsyncedWorkTest {
    @Test
    fun pendingPhotoCountsAsUnsynced() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = PowerSyncDatabase(DatabaseDriverFactory(ctx), LoopDoSchema.schema, dbFilename = "test-${UUID.randomUUID()}.db")
        try {
            assertFalse(hasUnsyncedWork(db))
            db.execute("INSERT INTO photo_files (id, path, uploaded) VALUES (?, '/x.webp', 1)", listOf(UUID.randomUUID().toString()))
            assertFalse("an uploaded photo is safe", hasUnsyncedWork(db))
            db.execute("INSERT INTO photo_files (id, path, uploaded) VALUES (?, '/y.webp', 0)", listOf(UUID.randomUUID().toString()))
            assertTrue(hasUnsyncedWork(db))
        } finally { db.close() }
    }
}
