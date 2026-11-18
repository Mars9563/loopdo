package app.loopdo.server

import org.slf4j.LoggerFactory
import java.util.UUID

/** Deletes every R2 object under a prefix; returns how many. */
interface R2Lister {
    fun deletePrefix(prefix: String): Int
}

data class WipeReport(val claimed: Int, val photosDeleted: Int, val wiped: Int, val live: Boolean)

/**
 * M9 (design §3.4): once a day, accounts whose deletion was asked 7+ days ago are claimed (keep is refused from then
 * on), their photos removed from R2, then their sign-in wiped (every table cascades). Until [live] is switched on
 * (env LOOPDO_WIPE_LIVE, owner's OK after the M9 manual test) it only claims and logs: nothing is deleted.
 * Every step can run again: a failed photo delete is retried the next day; the wipe only touches claimed accounts.
 */
class WipeJob(private val db: Db, private val r2: R2Lister?, private val live: Boolean) {
    private val log = LoggerFactory.getLogger("loopdo.wipe")

    suspend fun runOnce(): WipeReport {
        val claimed = db.asService { c ->
            c.prepareStatement("select * from public.claim_due_accounts()").use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } } }
        }
        if (!live) {
            if (claimed.isNotEmpty()) log.info("would wipe: ${claimed.size} account(s) (dry-run)")
            return WipeReport(claimed.size, 0, 0, live = false)
        }
        // Photos first: if R2 can't be reached, nothing is wiped today and it is tried again tomorrow.
        var photos = 0
        val cleaned = claimed.filter { id ->
            runCatching { photos += r2?.deletePrefix("u/$id/") ?: 0 }
                .onFailure { log.warn("photo delete failed for one account; retried tomorrow (${it.javaClass.simpleName})") }
                .isSuccess
        }
        if (cleaned.isEmpty()) return WipeReport(claimed.size, photos, 0, live = true)
        val wiped = db.asService { c ->
            c.prepareStatement("select public.wipe_claimed_accounts(?)").use { st ->
                st.setArray(1, c.createArrayOf("uuid", cleaned.toTypedArray()))
                st.executeQuery().use { it.next(); it.getInt(1) }
            }
        }
        log.info("wiped: $wiped account(s), $photos photo(s)")
        return WipeReport(claimed.size, photos, wiped, live = true)
    }
}

/** R2 through the S3 API: list everything under the prefix and delete it, 1000 keys at a time. */
class S3R2Lister(r2: R2Config) : R2Lister {
    private val bucket = r2.bucket
    private val s3 = software.amazon.awssdk.services.s3.S3Client.builder()
        .endpointOverride(java.net.URI("https://${r2.accountId}.r2.cloudflarestorage.com"))
        .region(software.amazon.awssdk.regions.Region.of("auto"))
        .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(r2.accessKeyId, r2.secretAccessKey)))
        .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create())
        .build()

    override fun deletePrefix(prefix: String): Int {
        var deleted = 0
        s3.listObjectsV2Paginator { it.bucket(bucket).prefix(prefix) }.forEach { page ->
            val keys = page.contents().map { o -> software.amazon.awssdk.services.s3.model.ObjectIdentifier.builder().key(o.key()).build() }
            if (keys.isNotEmpty()) {
                val r = s3.deleteObjects { it.bucket(bucket).delete { d -> d.objects(keys) } }
                // DeleteObjects answers 200 with per-key errors: any of them means "not done", so the wipe waits (review I2).
                if (r.hasErrors() && r.errors().isNotEmpty()) error("R2 refused ${r.errors().size} of ${keys.size} deletes")
                deleted += keys.size
            }
        }
        return deleted
    }
}
