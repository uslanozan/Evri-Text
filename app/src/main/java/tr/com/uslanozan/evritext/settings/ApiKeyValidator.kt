package tr.com.uslanozan.evritext.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tr.com.uslanozan.evritext.translate.LlmProvider
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Validates provider keys without generating content or consuming translation tokens. */
class ApiKeyValidator(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    enum class Result { VALID, INVALID, UNAVAILABLE }

    suspend fun validate(provider: LlmProvider, apiKey: String): Result = withContext(Dispatchers.IO) {
        val request = request(provider, apiKey)

        try {
            http.newCall(request).execute().use { response -> classify(response.code) }
        } catch (_: IOException) {
            Result.UNAVAILABLE
        }
    }

    companion object {
        internal fun classify(statusCode: Int): Result = when {
            statusCode in 200..299 -> Result.VALID
            statusCode == 400 || statusCode == 401 || statusCode == 403 -> Result.INVALID
            // The server has authenticated the request before applying its quota.
            statusCode == 429 -> Result.VALID
            else -> Result.UNAVAILABLE
        }

        internal fun request(provider: LlmProvider, apiKey: String): Request =
            Request.Builder().apply {
                when (provider) {
                    LlmProvider.GEMINI -> {
                        url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
                        // Keeping secrets out of URLs also keeps them out of proxy/access logs.
                        header("x-goog-api-key", apiKey)
                    }
                    LlmProvider.OPENAI -> {
                        url("https://api.openai.com/v1/models")
                        header("Authorization", "Bearer $apiKey")
                    }
                    LlmProvider.OPENROUTER -> {
                        url("https://openrouter.ai/api/v1/key")
                        header("Authorization", "Bearer $apiKey")
                    }
                    LlmProvider.ANTHROPIC -> {
                        url("https://api.anthropic.com/v1/models?limit=1")
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    LlmProvider.GROQ -> {
                        url("https://api.groq.com/openai/v1/models")
                        header("Authorization", "Bearer $apiKey")
                    }
                }
                get()
            }.build()
    }
}
