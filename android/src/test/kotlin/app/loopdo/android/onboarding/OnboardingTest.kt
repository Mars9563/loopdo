package app.loopdo.android.onboarding

import app.loopdo.android.auth.AuthState
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeSettingsApi(var fail: Exception? = null) : SettingsApi {
    val calls = mutableListOf<Pair<String, String>>()
    override suspend fun setDaySettings(timeZone: String, dayEnd: String) {
        fail?.let { throw it }
        calls += timeZone to dayEnd
    }
}

private class FakeProfile : ProfileWriter {
    var name: String? = null
    override suspend fun finishOnboarding(displayName: String, at: Instant) { name = displayName }
}

class OnboardingTest {
    private val user = AuthState.SignedIn(UUID.randomUUID(), "t")

    @Test fun signedOut() = assertEquals(StartState.SignedOut, startState(AuthState.SignedOut, hasSynced = null, hasSettings = false))
    @Test fun onboardingWaitsForFirstSync() = assertEquals(StartState.SyncingFirstTime, startState(user, hasSynced = false, hasSettings = false))
    @Test fun unknownSyncStateWaits() = assertEquals(StartState.SyncingFirstTime, startState(user, hasSynced = null, hasSettings = false))
    @Test fun newUserNeedsOnboarding() = assertEquals(StartState.NeedsOnboarding, startState(user, hasSynced = true, hasSettings = false))
    @Test fun existingUserSkipsOnboarding() = assertEquals(StartState.Ready, startState(user, hasSynced = true, hasSettings = true))
    @Test fun settingsPresentBeforeSyncFlagIsReady() = assertEquals(StartState.Ready, startState(user, hasSynced = false, hasSettings = true))

    @Test
    fun dayEndOutsideRangeRejected() = runTest {
        val api = FakeSettingsApi()
        val r = Onboarding(api, FakeProfile()).submit(ZoneId.of("Asia/Kolkata"), LocalTime.of(8, 30), "Kaze")
        assertEquals("the day can end at 08:00 at the latest.", r.exceptionOrNull()?.message)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun blankNameRejected() = runTest {
        val r = Onboarding(FakeSettingsApi(), FakeProfile()).submit(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0), "  ")
        assertEquals("tell us what to call you.", r.exceptionOrNull()?.message)
    }

    @Test
    fun offlineSubmitShowsMessage() = runTest {
        val profile = FakeProfile()
        val r = Onboarding(FakeSettingsApi(IOException("offline")), profile).submit(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0), "Kaze")
        assertEquals("setup needs a connection once. connect and tap [start] again.", r.exceptionOrNull()?.message)
        assertEquals(null, profile.name) // nothing half-done
    }

    @Test
    fun submitSendsSettingsThenName() = runTest {
        val api = FakeSettingsApi(); val profile = FakeProfile()
        assertTrue(Onboarding(api, profile).submit(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0), " Kaze ").isSuccess)
        assertEquals(listOf("Asia/Kolkata" to "04:00"), api.calls)
        assertEquals("Kaze", profile.name)
    }

    @Test
    fun zonesAreRealAndSearchable() {
        val zones = Zones.all()
        assertTrue("Asia/Kolkata" in zones)
        assertFalse(zones.any { it.startsWith("posix/") || it.startsWith("right/") })
        assertEquals(zones.sorted(), zones)
        assertTrue(Zones.search("kolk").contains("Asia/Kolkata"))
        assertTrue(Zones.search("new york").contains("America/New_York"))
    }

    @Test
    fun exampleSentenceExplainsTheBoundary() = assertEquals(
        "late nights count as the same day. right now (18:41) it is sun 15 nov; at 02:00 tonight it will still be sun 15 nov.",
        Onboarding.example(Instant.parse("2026-11-15T13:11:00Z"), ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0)),
    )
}
