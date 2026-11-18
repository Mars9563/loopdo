package app.loopdo.client

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientModuleTest {
    @Test
    fun `the client module is on the classpath`() = assertEquals("client", CLIENT_MODULE)
}
