package app.loopdo.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/** The colours are the locked design's (docs/design/app/README.md); this keeps them from drifting. */
class TokensTest {
    @Test
    fun colorsMatchLockedDesign() {
        assertEquals(Color(0xFFDBE6F5), LoopDoColors.text)
        assertEquals(Color(0xFF9CC4FF), LoopDoColors.accent)
        assertEquals(Color(0xFF7F8DA3), LoopDoColors.dim)
        assertEquals(Color(0xFF5F6B7D), LoopDoColors.done)
        assertEquals(Color(0xFFFF8A80), LoopDoColors.warn)
        assertEquals(Color(0xCC04080E), LoopDoColors.tint)
    }
}
