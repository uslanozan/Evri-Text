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
 * Gemini over the REST API, based on the behavior validated by the first prototype.
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
                GeminiReply.parseIndexed(reply, sentences.size).forEachIndexed { index, value ->
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
        val request = Request.Builder()
            .url("$ENDPOINT/$model:generateContent")
            // API keys in URLs can leak into proxy and access logs.
            .header("x-goog-api-key", apiKey)
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
     * Honours the server's own retryDelay on 429, else backs off exponentially. The
     * free tier allows 15 requests/minute and the reply carries the exact wait.
     */
    internal fun retryDelayMs(error: String?, attempt: Int): Long {
        val text = error.orEmpty()
        if ("RESOURCE_EXHAUSTED" in text || "429" in text) {
            // Quote style varies with who serialised the error on the way here.
            val seconds = Regex("""retryDelay['"]?\s*:\s*['"]?(\d+)s""")
                .find(text)?.groupValues?.get(1)
                ?: Regex("""[Rr]etry in ([\d.]+)s""").find(text)?.groupValues?.get(1)
            return min((seconds?.toDoubleOrNull()?.times(1000)?.toLong() ?: 30_000L) + 1_000L, 65_000L)
        }
        return min(2_000L * attempt, 10_000L)
    }

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
