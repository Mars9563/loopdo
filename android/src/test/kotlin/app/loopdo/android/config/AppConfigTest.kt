package app.loopdo.android.config

import kotlin.test.Test
import kotlin.test.assertEquals

/** D1: the public client config moves out of BuildConfig into plain constants; the values must not change. */
class AppConfigTest {
    @Test
    fun `config values are unchanged`() {
        assertEquals("https://jcnenvgdlryxlhsnkaiq.supabase.co", AppConfig.supabaseUrl)
        assertEquals("sb_publishable_s1x-441V8Vb2tHHivRNPIQ_x61QCk8b", AppConfig.publishableKey)
        assertEquals("https://6af99fc3e5558a7422ba3c92.powersync.journeyapps.com", AppConfig.powerSyncUrl)
        assertEquals("https://loopdo.mrsdev.in", AppConfig.apiUrl)
        assertEquals("408208720715-3pr2op2dbtdhijoq1jv9cr0k2dk7m2u2.apps.googleusercontent.com", AppConfig.googleWebClientId)
    }
}
