package tr.com.uslanozan.evritext.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Provider errors that cannot improve by repeating the same request. */
internal class NonRetryableProviderException(message: String) : IllegalStateException(message)

internal data class GeminiQuotaDetails(
    val dailyLimitExceeded: Boolean,
    val retryDelayMs: Long?,
)

internal fun parseGeminiQuotaDetails(body: String): GeminiQuotaDetails {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return GeminiQuotaDetails(dailyLimitExceeded = false, retryDelayMs = null)
    val error = root["error"] as? JsonObject
    val details = error?.get("details") as? JsonArray ?: JsonArray(emptyList())
    val daily = details.asSequence()
        .mapNotNull { it as? JsonObject }
        .filter { (it["@type"] as? JsonPrimitive)?.content?.endsWith("QuotaFailure") == true }
        .flatMap { (it["violations"] as? JsonArray).orEmpty().asSequence() }
        .mapNotNull { it as? JsonObject }
        .any { violation ->
            val quotaId = (violation["quotaId"] as? JsonPrimitive)?.content.orEmpty()
            val description = (violation["description"] as? JsonPrimitive)?.content.orEmpty()
            "PerDay" in quotaId || "per day" in description.lowercase() ||
                "daily quota" in description.lowercase()
        }
    val retryDelay = details.asSequence()
        .mapNotNull { it as? JsonObject }
        .firstOrNull { (it["@type"] as? JsonPrimitive)?.content?.endsWith("RetryInfo") == true }
        ?.get("retryDelay")
        ?.let { (it as? JsonPrimitive)?.content }
        ?.let(::durationMillis)
    return GeminiQuotaDetails(dailyLimitExceeded = daily, retryDelayMs = retryDelay)
}

private fun durationMillis(value: String): Long? {
    val match = Regex("""^(\d+(?:\.\d+)?)(ms|s|m)$""").matchEntire(value) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2]) {
        "ms" -> 1.0
        "s" -> 1000.0
        "m" -> 60_000.0
        else -> return null
    }
    return (amount * multiplier).toLong().takeIf { it >= 0L }
}
