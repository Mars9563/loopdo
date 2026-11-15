package app.loopdo.server

import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.UUID
import javax.sql.DataSource

/** The database (or the Supabase pooler) can't be reached: the phone should retry later. */
class DbUnavailable(cause: Throwable) : RuntimeException("database unavailable", cause)

/**
 * Logged in as `loopdo_api`, which may only SET ROLE `loopdo_user`. Every request
 * runs as that user inside one transaction, with the caller's id in the claims, so
 * the database's row-level security applies to everything the API does.
 */
class Db(private val dataSource: DataSource) {
    fun <T> asUser(userId: UUID, block: (Connection) -> T): T {
        val conn = try {
            dataSource.connection
        } catch (e: SQLException) {
            throw DbUnavailable(e)
        }
        conn.use { c ->
            try {
                c.autoCommit = false
                c.createStatement().use { it.execute("set local role loopdo_user") }
                c.prepareStatement("select set_config('request.jwt.claims', ?, true)").use {
                    it.setString(1, """{"sub":"$userId","role":"authenticated"}""")
                    it.execute()
                }
                val result = block(c)
                c.commit()
                return result
            } catch (e: Throwable) {
                runCatching { c.rollback() }
                if (e is SQLException && e.isConnectionProblem()) throw DbUnavailable(e)
                throw e
            }
        }
    }
}

/** SQLSTATE class 08 = connection exception; 57P = server shutting down. */
private fun SQLException.isConnectionProblem(): Boolean =
    this is SQLTransientConnectionException || sqlState?.startsWith("08") == true || sqlState?.startsWith("57P") == true
