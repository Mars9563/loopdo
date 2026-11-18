package app.loopdo.desktop

import org.junit.Assume.assumeTrue
import java.io.File

/** The throwaway test user (never the owner's account); its password stays in the file and is never printed. */
object LiveTestUser {
    private val values: Map<String, String> by lazy {
        val f = File(System.getProperty("user.home"), ".config/loopdo/test-user.env")
        if (!f.exists()) emptyMap() else f.readLines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }
    val email get() = values.getValue("LOOPDO_TEST_EMAIL")
    val password get() = values.getValue("LOOPDO_TEST_PASSWORD")
    val userId get() = values.getValue("LOOPDO_TEST_USER_ID")

    fun assumeLive() {
        assumeTrue("live tests run with -Ploopdo.live=1", System.getProperty("loopdo.live") == "1")
        assumeTrue("needs ~/.config/loopdo/test-user.env", values.isNotEmpty())
    }
}
