package tr.com.uslanozan.evritext.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

/**
 * Turns a model reply into one translation per input slot.
 *
 * Split out from the provider so it can be tested directly: every rule here exists
 * because a real reply broke a real translation during Phase 0, and a silent
 * regression would show up as a film with English patches in it rather than as a
 * crash.
 */
internal object GeminiReply {

    /** Slots the model failed to return come back null so the caller can fill them. */
    fun parseIndexed(raw: String, expected: Int): List<String?> {
        if (raw.isBlank()) error("empty response")

        val text = raw.trim().removeFence()
        // Models sometimes append a second array or a trailing note. A strict parse
        // would throw away a reply that was otherwise complete.
        val json = firstJsonValue(text) ?: error("no JSON in reply")

        var data = Json.parseToJsonElement(json)
        if (data is JsonObject) {
            val wrapped = listOf("items", "translations", "result")
                .firstNotNullOfOrNull { key -> (data as JsonObject)[key] }
            if (wrapped != null) data = wrapped
        }
        val array = data as? JsonArray ?: error("expected a JSON array")

        val out = arrayOfNulls<String>(expected)
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val index = (obj["i"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }
                ?: continue
            if (index !in 0 until expected) continue
            // "tr" is what the prompt asks for; the rest are what models actually
            // produce often enough to be worth accepting rather than failing on.
            for (field in FIELDS) {
                val value = (obj[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!value.isNullOrBlank()) {
                    out[index] = value
                    break
                }
            }
        }
        if (out.all { it == null }) error("no usable translations in response")
        return out.toList()
    }

    /** Scans out one complete JSON array or object, ignoring anything after it. */
    fun firstJsonValue(text: String): String? {
        val start = text.indexOfFirst { it == '[' || it == '{' }
        if (start < 0) return null
        val open = text[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == open -> depth += 1
                c == close -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun String.removeFence(): String {
        if (!startsWith("```")) return this
        return substringAfter('\n').substringBeforeLast("```").trim()
    }

    private val FIELDS = listOf("tr", "translation", "text", "t")
}
