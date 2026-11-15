package app.loopdo.server

import kotlin.test.Test
import kotlin.test.assertEquals

class DbTest {
    @Test
    fun asUserSeesOnlyOwnRows() {
        val db = Db(TestDb.apiDataSource())
        val titles = db.asUser(TestDb.userA) { c ->
            c.createStatement().executeQuery("select title from public.habits").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        assertEquals(listOf("meditate"), titles)
    }
}
