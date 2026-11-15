package app.loopdo.server

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration

/**
 * Short-lived signed URLs for Cloudflare R2, so photos go straight between the
 * phone and R2 and never pass through (or fill) the server. Signing is offline.
 */
class PhotoUrls(private val r2: R2Config) {
    private val presigner: S3Presigner = S3Presigner.builder()
        .endpointOverride(URI("https://${r2.accountId}.r2.cloudflarestorage.com"))
        .region(Region.of("auto"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(r2.accessKeyId, r2.secretAccessKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()

    /** The phone must send exactly this content type and length. */
    fun put(key: String, contentType: String, bytes: Long): String =
        presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(15))
            .putObjectRequest(PutObjectRequest.builder().bucket(r2.bucket).key(key).contentType(contentType).contentLength(bytes).build())
            .build()).url().toString()

    fun get(key: String): String =
        presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .getObjectRequest(GetObjectRequest.builder().bucket(r2.bucket).key(key).build())
            .build()).url().toString()
}
