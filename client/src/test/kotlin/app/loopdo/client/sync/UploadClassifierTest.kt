package app.loopdo.client.sync


import kotlin.test.Test
import kotlin.test.assertEquals

class UploadClassifierTest {
    @Test fun ok() = assertEquals(UploadOutcome.Applied, UploadClassifier.classify(200, """{"applied":2}"""))
    @Test fun rejected() = assertEquals(UploadOutcome.Rejected("BACKDATED", 0), UploadClassifier.classify(422, """{"code":"BACKDATED","opIndex":0}"""))
    // Review of M6.5: a request the server couldn't read at all (opIndex -1, e.g. an older server and a newer app) is
    // retried like a server bug (set aside only after a day), never discarded at once.
    @Test fun unreadableRequestIsRetried() = assertEquals(UploadOutcome.ServerBug, UploadClassifier.classify(422, """{"code":"BAD_OP","opIndex":-1}"""))
    @Test fun settingsRequiredIsRetry() = assertEquals(UploadOutcome.RetryLater, UploadClassifier.classify(422, """{"code":"SETTINGS_REQUIRED","opIndex":0}"""))
    @Test fun unavailableIsRetry() = assertEquals(UploadOutcome.RetryLater, UploadClassifier.classify(503, """{"code":"UNAVAILABLE"}"""))
    @Test fun expiredTokenIsRetry() = assertEquals(UploadOutcome.RetryLater, UploadClassifier.classify(401, null))
    @Test fun serverBug() = assertEquals(UploadOutcome.ServerBug, UploadClassifier.classify(500, "boom"))
    @Test fun garbled422IsServerBug() = assertEquals(UploadOutcome.ServerBug, UploadClassifier.classify(422, "<html>"))
}
