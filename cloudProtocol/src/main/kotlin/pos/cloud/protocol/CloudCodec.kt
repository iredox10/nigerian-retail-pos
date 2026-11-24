package pos.cloud.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Shared by Android, desktop and server so keys and request fingerprints have identical bytes. */
object CloudCodec {
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }
    inline fun <reified T> encode(value: T): String = bounded(json.encodeToString(value))
    inline fun <reified T> decode(value: String): T = json.decodeFromString(bounded(value))
    inline fun <reified T> fingerprint(value: T): String = sha256(canonical(json.encodeToJsonElement(value)).toString())
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @PublishedApi internal fun bounded(value: String): String {
        require(value.length <= CloudLimits.MAX_PAYLOAD_BYTES && value.toByteArray(Charsets.UTF_8).size <= CloudLimits.MAX_PAYLOAD_BYTES) { "Cloud payload exceeds the supported size. Local records have not been discarded." }
        return value
    }
    @PublishedApi internal fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
