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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Shared retry, prompt and 1:1 response handling for non-Gemini providers. */
internal abstract class JsonTranslationProvider(
    final override val id: String,
    final override val displayName: String,
    private val maxAttempts: Int,
) : TranslationProvider {

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
        val payload = translationPayload(sentences, context)
        val best = arrayOfNulls<String>(sentences.size)
        var lastError: String? = null

        for (attempt in 1..maxAttempts) {
            try {
                GeminiReply.parseIndexed(request(system, payload), sentences.size)
                    .forEachIndexed { index, value ->
                        if (best[index] == null && value != null) best[index] = value
                    }
                if (best.all { it != null }) {
                    return@withContext best.map { it!!.replace(TURN_MARKER, "\n") }
                }
                Log.i(TAG, "$displayName attempt $attempt returned ${best.count { it != null }}/${sentences.size}")
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "$displayName attempt $attempt/$maxAttempts failed: ${e.message?.take(160)}")
            }
            if (attempt < maxAttempts) delay(retryDelayMs(lastError, attempt))
        }

        val translated = best.count { it != null }
        if (translated == 0) error("translation failed after $maxAttempts attempts: $lastError")
        Log.w(TAG, "$displayName keeping partial chunk: $translated/${sentences.size}")
        best.mapIndexed { index, value ->
            (value ?: sentences[index]).replace(TURN_MARKER, "\n")
        }
    }

    protected abstract fun request(system: String, payload: String): String

    private fun retryDelayMs(error: String?, attempt: Int): Long {
        if ("429" in error.orEmpty()) return 30_000L
        return min(2_000L * attempt, 10_000L)
    }

    private companion object {
        const val TAG = "LlmTranslate"
    }
}

/** OpenAI Chat Completions wire format, also implemented by OpenRouter and Groq. */
internal class OpenAiCompatibleTranslationProvider private constructor(
    private val apiKey: String,
    private val model: String,
    providerId: String,
    providerName: String,
    private val endpoint: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    maxAttempts: Int = 3,
    private val http: OkHttpClient = defaultHttpClient(),
) : JsonTranslationProvider(
    id = cacheId(providerId, model),
    displayName = providerName,
    maxAttempts = maxAttempts,
) {

    override fun request(system: String, payload: String): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", payload)
                }
            }
            put("max_completion_tokens", 4096)
        }.toString()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(400)}")
            val root = Json.parseToJsonElement(text) as? JsonObject ?: error("not an object")
            val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.get("message") as? JsonObject ?: error("no message")
            return message["content"].asText() ?: error("no text content")
        }
    }

    companion object {
        internal fun openAi(
            apiKey: String,
            model: String,
            http: OkHttpClient = defaultHttpClient(),
        ) = OpenAiCompatibleTranslationProvider(
            apiKey = apiKey,
            model = model,
            providerId = "openai",
            providerName = "OpenAI",
            endpoint = "https://api.openai.com/v1/chat/completions",
            http = http,
        )

        internal fun openRouter(
            apiKey: String,
            model: String,
            http: OkHttpClient = defaultHttpClient(),
        ) = OpenAiCompatibleTranslationProvider(
            apiKey = apiKey,
            model = model,
            providerId = "openrouter",
            providerName = "OpenRouter",
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://evritext.uslanozan.com.tr/",
                "X-Title" to "Evri Text",
            ),
            http = http,
        )

        internal fun groq(
            apiKey: String,
            model: String,
            http: OkHttpClient = defaultHttpClient(),
        ) = OpenAiCompatibleTranslationProvider(
            apiKey = apiKey,
            model = model,
            providerId = "groq",
            providerName = "Groq",
            endpoint = "https://api.groq.com/openai/v1/chat/completions",
            http = http,
        )
    }
}

/** Native Anthropic Messages API adapter. */
internal class AnthropicTranslationProvider(
    private val apiKey: String,
    private val model: String,
    maxAttempts: Int = 3,
    private val http: OkHttpClient = defaultHttpClient(),
) : JsonTranslationProvider(
    id = cacheId("anthropic", model),
    displayName = "Anthropic",
    maxAttempts = maxAttempts,
) {

    override fun request(system: String, payload: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("temperature", 0.3)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", payload)
                }
            }
        }.toString()
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(400)}")
            val root = Json.parseToJsonElement(text) as? JsonObject ?: error("not an object")
            val content = root["content"] as? JsonArray ?: error("no content")
            return content.mapNotNull { block ->
                val obj = block as? JsonObject
                if ((obj?.get("type") as? JsonPrimitive)?.content == "text") {
                    (obj["text"] as? JsonPrimitive)?.content
                } else null
            }.joinToString("").ifBlank { error("no text content") }
        }
    }
}

private fun translationPayload(
    sentences: List<String>,
    context: TranslationContext,
): String = buildJsonObject {
    put("video_title", context.videoTitle)
    putJsonArray("preceding_context") { context.before.forEach { add(it) } }
    putJsonArray("following_context") { context.after.forEach { add(it) } }
    putJsonArray("items") {
        sentences.forEachIndexed { index, text ->
            addJsonObject {
                put("i", index)
                put("source", text)
            }
        }
    }
}.toString()

private fun kotlinx.serialization.json.JsonElement?.asText(): String? = when (this) {
    is JsonPrimitive -> content
    is JsonArray -> mapNotNull { block ->
        ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.content
    }.joinToString("").takeIf { it.isNotBlank() }
    else -> null
}

internal fun cacheId(provider: String, model: String): String =
    "$provider-${model.replace(Regex("[^A-Za-z0-9._-]"), "_")}".lowercase()

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

private fun defaultHttpClient() = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()
