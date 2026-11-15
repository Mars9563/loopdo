package app.loopdo.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DbTest {
    @Test
    fun asUserSeesOnlyOwnRows() {
        val db = Db(TestDb.apiDataSource())
        val ids = db.asUser(TestDb.userA) { c ->
            c.createStatement().executeQuery("select id from public.habits").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
        // Other tests add habits to the shared database, so compare with A's real rows.
        val aOwns = TestDb.scalar("select count(*) from public.habits where user_id = '${TestDb.userA}'").toInt()
        assertEquals(aOwns, ids.size)
        assertTrue(TestDb.habitA.toString() in ids)
        assertFalse(TestDb.habitB.toString() in ids)
    }
}
