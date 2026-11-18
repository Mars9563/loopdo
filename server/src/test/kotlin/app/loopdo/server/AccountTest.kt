package app.loopdo.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9 (design 2026-11-18 §3.4, §4): delete with a 7-day undo, the wipe job (dry-run first), the admin sign-up switch. */
class AccountTest {
    private val secret = "test-anchor-secret-32-bytes-long!!".toByteArray()

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { loopdoModule(Deps(Db(TestDb.apiDataSource()), TestAuth.jwkProvider, TestAuth.ISSUER, secret) { Instant.now() }) }
        block()
    }

    /** A throwaway account (the wipe really deletes it), with a habit and a journal day of its own. */
    private fun throwaway(): UUID {
        val u = UUID.randomUUID()
        TestDb.admin(
            """insert into auth.users (id, email, aud, role) values ('$u', '$u@test.local', 'authenticated', 'authenticated');
               insert into public.habits (id, user_id, title, kind, target) values (gen_random_uuid(), '$u', 'h', 'yes_no', 1);""",
        )
        return u
    }

    private fun requestedDaysAgo(u: UUID, days: Int) =
        TestDb.admin("update public.profiles set delete_requested_at = now() - interval '$days days' where id = '$u'")
    private fun exists(u: UUID) = TestDb.scalar("select count(*) from auth.users where id = '$u'") == "1"
    private fun requested(u: UUID) = TestDb.scalar("select coalesce(delete_requested_at::text, '') from public.profiles where id = '$u'")

    private suspend fun ApplicationTestBuilder.call(path: String, u: UUID, body: String? = null) = client.post(path) {
        bearerAuth(TestAuth.token(u))
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    private class FakeR2 : R2Lister {
        val deleted = mutableListOf<String>()
        override fun deletePrefix(prefix: String): Int { deleted += prefix; return 2 }
    }

    private fun job(live: Boolean, r2: R2Lister? = FakeR2()) = WipeJob(Db(TestDb.apiDataSource()), r2, live)

    @Test
    fun deleteSetsTheDate() = app {
        val u = throwaway()
        val r = call("/api/account/delete", u)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue("deleteOn" in r.bodyAsText())
        assertTrue(requested(u).isNotEmpty())
    }

    @Test
    fun keepClears() = app {
        val u = throwaway()
        call("/api/account/delete", u)
        assertEquals(HttpStatusCode.OK, call("/api/account/keep", u).status)
        assertEquals("", requested(u))
    }

    @Test
    fun keepAfterClaimRefused() = app {
        val u = throwaway()
        requestedDaysAgo(u, 8)
        runBlocking { job(live = false).runOnce() } // claims it
        val r = call("/api/account/keep", u)
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue("WIPE_STARTED" in r.bodyAsText())
    }

    @Test
    fun sixDaysIsNotDue() {
        val u = throwaway()
        requestedDaysAgo(u, 6)
        runBlocking { job(live = true).runOnce() }
        assertTrue(exists(u))
        assertEquals("", TestDb.scalar("select coalesce(wipe_claimed_at::text, '') from public.profiles where id = '$u'"))
    }

    @Test
    fun dryRunDeletesNothing() {
        val u = throwaway()
        requestedDaysAgo(u, 8)
        val r2 = FakeR2()
        val report = runBlocking { job(live = false, r2).runOnce() }
        assertTrue(exists(u))
        assertEquals(0, report.wiped)
        assertEquals(false, report.live)
        assertTrue(report.claimed >= 1)
        assertEquals(emptyList<String>(), r2.deleted) // dry-run touches no photos either
    }

    @Test
    fun liveWipesRowsAndAuthUser() {
        val u = throwaway()
        requestedDaysAgo(u, 8)
        val r2 = FakeR2()
        val report = runBlocking { job(live = true, r2).runOnce() }
        assertTrue(!exists(u))
        assertEquals("0", TestDb.scalar("select count(*) from public.habits where user_id = '$u'"))
        assertTrue(report.wiped >= 1)
        assertTrue("u/$u/" in r2.deleted)
    }

    @Test
    fun r2OnlyForClaimed() {
        val due = throwaway()
        val kept = throwaway()
        requestedDaysAgo(due, 8)
        requestedDaysAgo(kept, 3)
        val r2 = FakeR2()
        runBlocking { job(live = true, r2).runOnce() }
        assertTrue("u/$due/" in r2.deleted)
        assertTrue("u/$kept/" !in r2.deleted)
        assertTrue(exists(kept))
    }

    @Test
    fun adminOnlySignupSwitch() = app {
        val admin = throwaway()
        val other = throwaway()
        TestDb.admin("update public.profiles set is_admin = true where id = '$admin'")
        assertEquals(HttpStatusCode.Forbidden, call("/api/admin/signups", other, """{"enabled":false}""").status)
        assertEquals(HttpStatusCode.OK, call("/api/admin/signups", admin, """{"enabled":false}""").status)
        assertEquals("false", TestDb.scalar("select signups_enabled::text from public.app_config where id = 1"))
        val read = client.get("/api/admin/signups") { bearerAuth(TestAuth.token(admin)) }
        assertTrue("\"enabled\":false" in read.bodyAsText(), read.bodyAsText())
        call("/api/admin/signups", admin, """{"enabled":true}""")
        assertEquals("true", TestDb.scalar("select signups_enabled::text from public.app_config where id = 1"))
    }
}
