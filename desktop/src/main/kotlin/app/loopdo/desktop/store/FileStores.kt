package app.loopdo.desktop.store

import app.loopdo.client.store.KeyValueStore
import app.loopdo.client.time.AnchorStore
import app.loopdo.client.time.SignedAnchor
import app.loopdo.shared.time.TimeAnchor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * A small JSON map in one file (the laptop's SharedPreferences). Writes go to `<file>.tmp` and are renamed over the
 * file, so a power cut leaves the old or the new version, never half of one; an unreadable file reads as empty.
 */
class FileKeyValue(private val file: File) : KeyValueStore {
    private val lock = Any()
    private var map: Map<String, JsonPrimitive> = read()

    private fun read(): Map<String, JsonPrimitive> = runCatching {
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { it.value.jsonPrimitive }
    }.getOrDefault(emptyMap())

    private fun write(next: Map<String, JsonPrimitive>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JsonObject(next).toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        map = next
    }

    fun keys(): Set<String> = synchronized(lock) { map.keys.toSet() }
    fun getString(key: String): String? = synchronized(lock) { map[key]?.takeIf { it.isString }?.content }
    fun putString(key: String, value: String) = synchronized(lock) { write(map + (key to JsonPrimitive(value))) }
    override fun getLong(key: String): Long? = synchronized(lock) { map[key]?.longOrNull }
    override fun putLong(key: String, value: Long) = synchronized(lock) { write(map + (key to JsonPrimitive(value))) }
    override fun remove(key: String) = synchronized(lock) { if (key in map) write(map - key) }
}

/**
 * The signed server time anchor (the phone keeps it in SharedPreferences "loopdo_anchor"). Stored as ONE value and
 * written in one atomic rename, so a reader or a crash never sees the server time of one anchor with the timers or
 * signature of another (D2 review: a torn anchor makes ticks refused or land on the wrong day).
 */
class FileAnchorStore(file: File) : AnchorStore {
    private val kv = FileKeyValue(file)

    override fun current(): SignedAnchor? = runCatching {
        val o = Json.parseToJsonElement(kv.getString(KEY)!!).jsonObject
        SignedAnchor(
            TimeAnchor(Instant.parse(o.getValue("server_time").jsonPrimitive.content), o.getValue("elapsed").jsonPrimitive.content.toLong(), o.getValue("boot").jsonPrimitive.content.toInt()),
            o.getValue("sig").jsonPrimitive.content,
        )
    }.getOrNull()

    override fun save(a: SignedAnchor) = kv.putString(
        KEY,
        JsonObject(
            mapOf(
                "server_time" to JsonPrimitive(a.anchor.serverTime.toString()), "elapsed" to JsonPrimitive(a.anchor.elapsedRealtimeMs),
                "boot" to JsonPrimitive(a.anchor.bootCount), "sig" to JsonPrimitive(a.sig),
            ),
        ).toString(),
    )

    private companion object { const val KEY = "anchor" }
}
