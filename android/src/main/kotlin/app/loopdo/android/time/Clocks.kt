package app.loopdo.android.time

import app.loopdo.shared.time.TimeAnchor
import app.loopdo.shared.time.estimateTime
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate

/** The phone's clocks. Only the wall clock can be changed by the user. */
interface DeviceClocks {
    fun wallClock(): Instant
    /** Milliseconds since boot; can't be changed and keeps counting in deep sleep. */
    fun elapsedRealtimeMs(): Long
    fun bootCount(): Int
    /** Network (NITZ/NTP) time, ignoring a manually changed clock; null if not known. */
    fun networkTime(): Instant?
}

/** A server time anchor plus the server's signature over it (GET /api/time). */
data class SignedAnchor(val anchor: TimeAnchor, val sig: String)

interface AnchorStore {
    fun current(): SignedAnchor?
    fun save(a: SignedAnchor)
}

/** GET /api/time → (serverTime, sig). Anchors come ONLY from here (contract). */
fun interface TimeApi {
    suspend fun time(): Pair<Instant, String>
}

class AnchorRefresher(private val api: TimeApi, private val store: AnchorStore, private val clocks: DeviceClocks) {
    /** Pairs the server time with the phone's timers read when the response arrives. */
    suspend fun refresh() {
        val (serverTime, sig) = api.time()
        store.save(SignedAnchor(TimeAnchor(serverTime, clocks.elapsedRealtimeMs(), clocks.bootCount()), sig))
    }
}

data class TrustedNow(val instant: Instant, val verified: Boolean)

/** "Now" that a changed phone clock can't move: network time, else the anchor + time since. */
class TrustedClock(private val clocks: DeviceClocks, private val anchors: AnchorStore) {
    fun now(): TrustedNow {
        val est = estimateTime(clocks.networkTime(), anchors.current()?.anchor, clocks.elapsedRealtimeMs(), clocks.bootCount())
        return if (est != null) TrustedNow(est, true) else TrustedNow(clocks.wallClock(), false)
    }
}

/** The `evidence` object every tick carries (ROADMAP contract; checked by the server's validateClaim). */
class EvidenceFactory(private val clocks: DeviceClocks, private val anchors: AnchorStore) {
    fun forTick(claimedDate: LocalDate): JsonObject {
        val signed = anchors.current()
        val net = clocks.networkTime()
        return buildJsonObject {
            put("claimedDate", claimedDate.toString())
            put("wallClock", clocks.wallClock().toString())
            if (net != null) put("networkTime", net.toString())
            if (signed != null) putJsonObject("anchor") {
                put("serverTime", signed.anchor.serverTime.toString())
                put("elapsedRealtimeMs", signed.anchor.elapsedRealtimeMs)
                put("bootCount", signed.anchor.bootCount)
                put("sig", signed.sig)
            }
            put("elapsedAtTickMs", clocks.elapsedRealtimeMs())
            put("bootCountAtTick", clocks.bootCount())
        }
    }
}

/** GET {api}/api/time with the user's token. */
class KtorTimeApi(private val http: io.ktor.client.HttpClient, private val token: suspend () -> String?) : TimeApi {
    override suspend fun time(): Pair<Instant, String> {
        val t = token() ?: throw java.io.IOException("not signed in")
        val body = http.get("${app.loopdo.android.config.AppConfig.apiUrl}/api/time") { bearerAuth(t) }.bodyAsText()
        val o = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        return Instant.parse(o.getValue("serverTime").jsonPrimitive.content) to o.getValue("sig").jsonPrimitive.content
    }
}
