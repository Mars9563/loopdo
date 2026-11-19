package app.loopdo.client.auth


import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A stand-in for Supabase Auth: one stored account, sessions, codes and its error codes. */
private class FakeBackend(initial: BackendStatus = BackendStatus.SignedOut) : AuthBackend {
    override val status = MutableStateFlow(initial)
    val userId: UUID = UUID.randomUUID()
    var password = "correct-horse"
    var confirmed = true
    var pendingCode: String? = null
    var offline = false
    var signupsClosed = false

    private fun online() { if (offline) throw IOException("no route to host") }
    private fun signedIn() { status.value = BackendStatus.SignedIn(userId, "token-$userId") }

    override suspend fun signInWithPassword(email: String, password: String) {
        online()
        if (password != this.password) throw BackendError("invalid_credentials", 400, "Invalid login credentials")
        if (!confirmed) throw BackendError("email_not_confirmed", 400, "Email not confirmed")
        signedIn()
    }
    override suspend fun signUp(email: String, password: String) {
        online()
        if (signupsClosed) throw BackendError(null, 403, "Sign-ups are closed")
        this.password = password; confirmed = false; pendingCode = "481516"
    }
    override suspend fun verifySignupCode(email: String, code: String) {
        online()
        if (code != pendingCode) throw BackendError("otp_expired", 403, "Token has expired or is invalid")
        confirmed = true; signedIn()
    }
    var codesSent = 0
    override suspend fun resendSignupCode(email: String) { online(); codesSent++; pendingCode = "999000" }
    override suspend fun sendPasswordReset(email: String) { online(); pendingCode = "234234" }
    override suspend fun resetPasswordWithCode(email: String, code: String, newPassword: String) {
        online()
        if (code != pendingCode) throw BackendError("otp_expired", 403, "expired")
        password = newPassword; signedIn()
    }
    override suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String) { online(); signedIn() }
    /** The laptop's browser flow: abandoned (tab closed, cancelled on Google, 3-min timeout) or a surprise error. */
    var google: String = "ok"
    override suspend fun signInWithGoogleBrowser() {
        online()
        when (google) {
            "abandoned" -> throw BrowserAbandoned()
            "surprise" -> throw IllegalStateException("ktor said no")
            else -> signedIn()
        }
    }
    override suspend fun signOut() { online(); status.value = BackendStatus.SignedOut }
    var refreshes = 0
    var refreshedToken = "refreshed-token"
    override suspend fun refreshSession(): String? { online(); refreshes++; return refreshedToken }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {
    private fun repo(backend: FakeBackend, scope: TestScope) = AuthRepository(backend, scope.backgroundScope)

    @Test
    fun wrongPasswordShowsReadableError() = runTest(UnconfinedTestDispatcher()) {
        val r = repo(FakeBackend(), this).signIn("kaze@example.com", "nope")
        assertEquals("wrong email or password. try again or [reset].", r.exceptionOrNull()?.message)
    }

    @Test
    fun signUpThenCodeSignsIn() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend(); val auth = repo(b, this)
        assertTrue(auth.signUp("kaze@example.com", "a-long-password").isSuccess)
        assertIs<AuthState.SignedOut>(auth.session.value) // not signed in until the code is entered
        assertEquals("that code is wrong or expired. check it, or tap [resend].", auth.verifyCode("kaze@example.com", "000000").exceptionOrNull()?.message)
        assertTrue(auth.verifyCode("kaze@example.com", "481516").isSuccess)
        assertEquals(AuthState.SignedIn(b.userId, "token-${b.userId}"), auth.session.first { it is AuthState.SignedIn })
    }

    @Test
    fun signOutClearsState() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend(); val auth = repo(b, this)
        auth.signIn("kaze@example.com", "correct-horse")
        auth.signOut()
        assertIs<AuthState.SignedOut>(auth.session.value)
    }

    @Test
    fun sessionRestoresOnStart() = runTest(UnconfinedTestDispatcher()) {
        // supabase-kt loads the saved session from storage; no network needed.
        val id = UUID.randomUUID()
        val b = FakeBackend(BackendStatus.SignedIn(id, "saved")).apply { offline = true }
        assertEquals(AuthState.SignedIn(id, "saved"), repo(b, this).session.value)
    }

    @Test
    fun offlineAndClosedSignupsAreReadable() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend().apply { signupsClosed = true }
        assertEquals("sign-ups are closed.", repo(b, this).signUp("x@example.com", "a-long-password").exceptionOrNull()?.message)
        b.offline = true
        assertEquals("no connection. try again when you're online.", repo(b, this).signIn("x@example.com", "y").exceptionOrNull()?.message)
    }

    @Test
    fun resetByCodeSignsInWithNewPassword() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend(); val auth = repo(b, this)
        assertTrue(auth.sendReset("kaze@example.com").isSuccess)
        assertTrue(auth.resetWithCode("kaze@example.com", "234234", "new-long-password").isSuccess)
        assertIs<AuthState.SignedIn>(auth.session.value)
        assertEquals("new-long-password", b.password)
    }

    @Test
    fun resendSendsANewCodeWithoutTouchingThePassword() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend(); val auth = repo(b, this)
        auth.signUp("kaze@example.com", "a-long-password")
        assertTrue(auth.resendCode("kaze@example.com").isSuccess)
        assertEquals(1, b.codesSent)
        assertEquals("a-long-password", b.password)
        assertTrue(auth.verifyCode("kaze@example.com", "999000").isSuccess)
    }

    /** A Supabase-style access token whose `sub` is [user] (signature not checked by the app). */
    private fun jwt(user: UUID): String {
        val b64 = { x: String -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(x.toByteArray()) }
        return b64("""{"alg":"ES256"}""") + "." + b64("""{"sub":"$user","exp":1}""") + ".c2ln"
    }

    @Test
    fun expiredSessionOfflineStaysSignedIn() = runTest(UnconfinedTestDispatcher()) {
        // Offline for longer than the token lifetime: refresh fails, the stored session remains.
        val user = UUID.randomUUID()
        val token = jwt(user)
        assertEquals(AuthState.SignedIn(user, token), repo(FakeBackend(BackendStatus.RefreshFailed(token)), this).session.value)
    }

    @Test
    fun refreshFailedWithoutStoredSessionIsSignedOut() = runTest(UnconfinedTestDispatcher()) {
        assertIs<AuthState.SignedOut>(repo(FakeBackend(BackendStatus.RefreshFailed(null)), this).session.value)
        assertIs<AuthState.SignedOut>(repo(FakeBackend(BackendStatus.RefreshFailed("not-a-jwt")), this).session.value)
    }

    @Test
    fun signOutOfflineIsReportedNotThrown() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend(); val auth = repo(b, this)
        auth.signIn("kaze@example.com", "correct-horse")
        b.offline = true
        assertEquals("no connection. signing out needs the network.", auth.signOut().exceptionOrNull()?.message)
        assertIs<AuthState.SignedIn>(auth.session.value)
    }

    @Test
    fun backgroundingKeepsTheSessionSignedIn() = runTest(UnconfinedTestDispatcher()) {
        // supabase-kt's Android lifecycle hook sets "Initializing" when the app goes to the background
        // (M4 Task 8): the widget and background sync must still see the signed-in user.
        val b = FakeBackend(); val auth = repo(b, this)
        auth.signIn("kaze@example.com", "correct-horse")
        b.status.value = BackendStatus.Loading
        assertEquals(AuthState.SignedIn(b.userId, "token-${b.userId}"), auth.session.value)
        b.status.value = BackendStatus.SignedOut // a real sign-out still signs out
        assertIs<AuthState.SignedOut>(auth.session.value)
    }

    private fun jwtExpiring(user: UUID, exp: java.time.Instant): String {
        val b64 = { x: String -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(x.toByteArray()) }
        return b64("""{"alg":"ES256"}""") + "." + b64("""{"sub":"$user","exp":${exp.epochSecond}}""") + ".c2ln"
    }
    private val now = java.time.Instant.parse("2026-11-16T06:00:00Z")

    /** After a night in deep sleep the stored token can be expired while the library still says "signed in" (final review I2). */
    @Test
    fun freshTokenRefreshesAnExpiringToken() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend(); val user = UUID.randomUUID()
        b.status.value = BackendStatus.SignedIn(user, jwtExpiring(user, now.plusSeconds(30)))
        assertEquals("refreshed-token", AuthRepository(b, backgroundScope) { now }.freshToken())
        assertEquals(1, b.refreshes)
    }

    @Test
    fun freshTokenKeepsAValidToken() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend(); val user = UUID.randomUUID()
        val token = jwtExpiring(user, now.plusSeconds(3600))
        b.status.value = BackendStatus.SignedIn(user, token)
        assertEquals(token, AuthRepository(b, backgroundScope) { now }.freshToken())
        assertEquals(0, b.refreshes)
    }

    @Test
    fun freshTokenOfflineFallsBackToTheStoredToken() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend(); val user = UUID.randomUUID()
        val token = jwtExpiring(user, now.minusSeconds(600))
        b.status.value = BackendStatus.SignedIn(user, token)
        b.offline = true // the server answers 401 → the upload retries later; nothing is lost
        assertEquals(token, AuthRepository(b, backgroundScope) { now }.freshToken())
    }

    @Test
    fun googleInTheBrowserAbandonedIsAPlainLineNeverAThrow() = runTest(UnconfinedTestDispatcher()) {
        val b = FakeBackend().apply { google = "abandoned" }
        assertEquals("nothing came back from the browser. try again.", repo(b, this).signInWithGoogleBrowser().exceptionOrNull()?.message)
        b.google = "surprise"
        assertEquals("google didn't finish. try again, or use your email and password.", repo(b, this).signInWithGoogleBrowser().exceptionOrNull()?.message)
        b.google = "ok"
        assertTrue(repo(b, this).signInWithGoogleBrowser().isSuccess)
    }
}
