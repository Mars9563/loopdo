package app.loopdo.android.notify

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.client.notify.CH_LAPTOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** D7: while the laptop is in use, the phone's pings land here: in the shade, no sound, no vibration, no pop-up. */
@RunWith(AndroidJUnit4::class)
class LaptopChannelTest {
    @Test
    fun theLaptopChannelIsQuiet() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ensureChannels(ctx)
        val ch = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(CH_LAPTOP)
        assertNotNull("channel exists", ch)
        assertEquals("while the laptop is in use", ch.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance)
        assertNull("no sound", ch.sound)
        assertFalse("no vibration", ch.shouldVibrate())
    }
}
