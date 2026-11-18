package app.loopdo.android

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Deletes the throwaway "test-<uuid>.db" files the device tests create (with their -wal/-shm), so test runs
 * don't fill the app's storage (72 MB had piled up by 2026-11-18). Call after closing the test's database.
 */
fun deleteTestDatabases() {
    val dir = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("x").parentFile ?: return
    dir.listFiles { f -> f.name.startsWith("test-") }?.forEach { it.delete() }
    // The reminder tests' throwaway stores ("test-reminders-<uuid>" SharedPreferences, M7).
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    ctx.dataDir.resolve("shared_prefs").listFiles { f -> f.name.startsWith("test-") }?.forEach { ctx.deleteSharedPreferences(it.name.removeSuffix(".xml")) }
    // The journal tests' stand-in photo files ("p-<uuid>.webp" in the cache).
    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.listFiles { f -> f.name.startsWith("p-") && f.name.endsWith(".webp") }
        ?.forEach { it.delete() }
}
