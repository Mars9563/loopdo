package app.loopdo.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.io.File
import java.time.Duration
import java.util.UUID

/** One throwaway Supabase Postgres (same major version as the cloud) shared by all tests. */
object TestDb {
    val userA: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    val userB: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    /** A brand-new user with no day settings yet (onboarding). */
    val userC: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    val habitA: UUID = UUID.fromString("10000000-0000-0000-0000-00000000000a")
    val habitB: UUID = UUID.fromString("10000000-0000-0000-0000-00000000000b")

    private val repoRoot = File(System.getProperty("loopdo.repoRoot"))

    private val container: GenericContainer<*> by lazy {
        GenericContainer("supabase/postgres:17.11.0.019")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withExposedPorts(5432)
            .withCopyFileToContainer(
                MountableFile.forHostPath(File(repoRoot, "supabase/migrations").absolutePath),
                "/loopdo/migrations",
            )
            .waitingFor(Wait.forLogMessage(".*init process complete.*", 1).withStartupTimeout(Duration.ofMinutes(3)))
            .also { it.start(); prepare(it) }
    }

    private fun psql(c: GenericContainer<*>, user: String, sql: String) {
        val r = c.execInContainer("psql", "-X", "-q", "-U", user, "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql)
        check(r.exitCode == 0) { "psql as $user failed: ${r.stderr}" }
    }

    private fun prepare(c: GenericContainer<*>) {
        // The image restarts once after init; wait until the real server answers.
        repeat(60) {
            if (c.execInContainer("pg_isready", "-U", "postgres", "-h", "localhost").exitCode == 0) return@repeat
            Thread.sleep(1000)
        }
        Thread.sleep(2000)
        File(repoRoot, "supabase/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
            .forEach { f ->
                val r = c.execInContainer("psql", "-X", "-q", "-U", "postgres", "-d", "postgres",
                    "-v", "ON_ERROR_STOP=1", "-f", "/loopdo/migrations/${f.name}")
                check(r.exitCode == 0) { "migration ${f.name} failed: ${r.stderr}" }
            }
        psql(c, "postgres", "alter role loopdo_api password 'test'")
        psql(c, "supabase_admin", """
            insert into auth.users (id, email, aud, role) values
              ('$userA', 'a@test.local', 'authenticated', 'authenticated'),
              ('$userB', 'b@test.local', 'authenticated', 'authenticated'),
              ('$userC', 'c@test.local', 'authenticated', 'authenticated');
            insert into public.habits (id, user_id, title, kind, target) values
              ('$habitA', '$userA', 'meditate', 'yes_no', 1),
              ('$habitB', '$userB', 'gym', 'yes_no', 1);
            -- A: India, day end 04:00 since forever. B: India, then New York from Sat 14 Nov 22:30 UTC.
            insert into public.day_settings_versions (user_id, effective_at, time_zone, day_end, floor_date) values
              ('$userA', 'epoch', 'Asia/Kolkata', '04:00', null),
              ('$userB', 'epoch', 'Asia/Kolkata', '04:00', null),
              ('$userB', '2026-11-14T22:30:00Z', 'America/New_York', '04:00', '2026-11-14');
        """.trimIndent())
    }

    /** Run SQL as the database superuser (fixtures only). */
    fun admin(sql: String) = psql(container, "supabase_admin", sql)

    /** One value from a query run as the superuser (assertions only). */
    fun scalar(sql: String): String {
        val r = container.execInContainer("psql", "-X", "-q", "-tA", "-U", "supabase_admin", "-d", "postgres", "-c", sql)
        check(r.exitCode == 0) { "query failed: ${r.stderr}" }
        return r.stdout.trim()
    }

    fun jdbcUrl(): String = "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/postgres"

    /**
     * A pool logged in as loopdo_api, exactly like production. One pool for the whole test run:
     * a new pool per test (never closed) ran the container out of connection slots.
     */
    private val sharedPool: HikariDataSource by lazy {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = jdbcUrl()
            username = "loopdo_api"
            password = "test"
            maximumPoolSize = 3
        })
    }

    fun apiDataSource(): HikariDataSource = sharedPool
}
