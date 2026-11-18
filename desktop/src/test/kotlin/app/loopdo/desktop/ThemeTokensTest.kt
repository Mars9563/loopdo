package app.loopdo.desktop

import androidx.compose.ui.graphics.Color
import app.loopdo.desktop.ui.Fern
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fern palette is exactly the locked desktop-v1 tokens. */
class ThemeTokensTest {
    @Test
    fun `fern tokens match the locked mockup`() {
        assertEquals(Color(0xFF0B111D), Fern.night)
        assertEquals(Color(0xFF3E9D73), Fern.leaf)
        assertEquals(Color(0xFF8FE3B8), Fern.mint)
        assertEquals(Color(0xFFD4EEE2), Fern.ink)
        assertEquals(Color(0xFFF1FBF6), Fern.hi)
        assertEquals(Color(0xFF7D9B90), Fern.lichen)
        assertEquals(Color(0xFF4C665E), Fern.moss)
        assertEquals(Color(0xFFFF9F87), Fern.ember)
        assertEquals(Color(0xFFF2CF7C), Fern.pollen)
    }
}
