package tr.com.uslanozan.evritext.translate

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Gemini over the REST API — port of `phase0/evri/translate.py`.
 *
 * Every defensive measure here exists because Phase 0 hit the failure it prevents on
 * a real 98-minute film; none of them are speculative.
 */
class GeminiTranslationProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val maxAttempts: Int = 3,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) : TranslationProvider {

    override val id = "gemini"
    override val displayName = "Google Gemini"

    override suspend fun translate(
        sentences: List<String>,
        sourceLang: String?,
        targetLang: String,
        context: TranslationContext,
    ): List<String> = withContext(Dispatchers.IO) {
        if (sentences.isEmpty()) return@withContext emptyList()

        val system = SYSTEM_INSTRUCTION
            .replace("{source}", sourceLang ?: "the source language (detect it)")
            .replace("{target}", targetLang)

        val payload = buildJsonObject {
            put("video_title", context.videoTitle)
            putJsonArray("preceding_context") { context.before.forEach { add(it) } }
            putJsonArray("following_context") { context.after.forEach { add(it) } }
            putJsonArray("items") {
                sentences.forEachIndexed { index, text ->
                    addJsonObject {
                        put("i", index)
                        // Deliberately NOT "text": models mirror the input key name
                        // back and answer with {"i","text"} instead of {"i","tr"},
                        // which silently produced 20 empty lines in Phase 0.
                        put("source", text)
                    }
                }
            }
        }

        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") { addJsonObject { put("text", payload.toString()) } }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("temperature", 0.3)
            }
        }.toString()

        val best = arrayOfNulls<String>(sentences.size)
        var lastError: String? = null

        for (attempt in 1..maxAttempts) {
            try {
                val reply = request(body)
                parseIndexed(reply, sentences.size).forEachIndexed { index, value ->
                    if (best[index] == null && value != null) best[index] = value
                }
                if (best.all { it != null }) {
                    return@withContext best.map { it!!.replace(TURN_MARKER, "\n") }
                }
                Log.i(TAG, "attempt $attempt returned ${best.count { it != null }}/${sentences.size}")
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "attempt $attempt/$maxAttempts failed: ${e.message?.take(160)}")
            }
            if (attempt < maxAttempts) delay(retryDelayMs(lastError, attempt))
        }

        val translated = best.count { it != null }
        if (translated == 0) error("translation failed after $maxAttempts attempts: $lastError")

        // Partial success beats discarding thirty good lines over one bad index.
        Log.w(TAG, "keeping partial chunk: $translated/${sentences.size}")
        best.mapIndexed { index, value ->
            (value ?: sentences[index]).replace(TURN_MARKER, "\n")
        }
    }

    private fun request(body: String): String {
        val url = "$ENDPOINT/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(400)}")
            val root = Json.parseToJsonElement(text) as? JsonObject ?: error("not an object")
            val candidates = root["candidates"] as? JsonArray ?: error("no candidates")
            val parts = (candidates.firstOrNull() as? JsonObject)
                ?.get("content")?.let { it as? JsonObject }
                ?.get("parts") as? JsonArray ?: error("no parts")
            return parts.mapNotNull {
                ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content
            }.joinToString("")
        }
    }

    /**
     * Parses into [expected] slots, leaving unreturned ones null so the caller can
     * fill just those from the source text.
     */
    private fun parseIndexed(raw: String, expected: Int): List<String?> {
        val text = raw.trim().removeFence()
        // Models sometimes append a second array or a trailing note; a strict parse
        // would throw away a reply that was otherwise complete.
        val json = firstJsonValue(text) ?: error("no JSON in reply")
        var data = Json.parseToJsonElement(json)
        if (data is JsonObject) {
            data = listOf("items", "translations", "result")
                .firstNotNullOfOrNull { data.jsonObjectOrNull()?.get(it) } ?: data
        }
        val array = data as? JsonArray ?: error("expected a JSON array")

        val out = arrayOfNulls<String>(expected)
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val index = (obj["i"] as? JsonPrimitive)?.let {
                runCatching { it.int }.getOrNull()
            } ?: continue
            if (index !in 0 until expected) continue
            // "tr" is what the prompt asks for; the rest are what models actually
            // produce often enough to be worth accepting rather than failing on.
            for (field in listOf("tr", "translation", "text", "t")) {
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

    /** Scans for one complete JSON array or object, ignoring anything after it. */
    private fun firstJsonValue(text: String): String? {
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

    private fun String.removeFence(): String {
        if (!startsWith("```")) return this
        return substringAfter('\n').substringBeforeLast("```").trim()
    }

    /**
     * Honours the server's own retryDelay on 429, else backs off exponentially. The
     * free tier allows 15 requests/minute and the reply carries the exact wait.
     */
    private fun retryDelayMs(error: String?, attempt: Int): Long {
        val text = error.orEmpty()
        if ("RESOURCE_EXHAUSTED" in text || "429" in text) {
            val seconds = Regex("""retryDelay"?:\s*"?(\d+)s""").find(text)?.groupValues?.get(1)
                ?: Regex("""[Rr]etry in ([\d.]+)s""").find(text)?.groupValues?.get(1)
            return min((seconds?.toDoubleOrNull()?.times(1000)?.toLong() ?: 30_000L) + 1_000L, 65_000L)
        }
        return min(2_000L * attempt, 10_000L)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    companion object {
        private const val TAG = "GeminiTranslate"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

        /**
         * Measured on a 30-sentence chunk: this 3.0 s, gemini-3.6-flash 15.2 s. Live
         * subtitles need the latency more than they need the extra quality.
         */
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}
