package tr.com.uslanozan.evritext.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Validates a key without generating content or consuming translation tokens. */
class GeminiApiKeyValidator(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    enum class Result { VALID, INVALID, UNAVAILABLE }

    suspend fun validate(apiKey: String): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$MODELS_ENDPOINT?pageSize=1")
            // Keeping secrets out of URLs also keeps them out of proxy/access logs.
            .header("x-goog-api-key", apiKey)
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response -> classify(response.code) }
        } catch (_: IOException) {
            Result.UNAVAILABLE
        }
    }

    companion object {
        private const val MODELS_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models"

        internal fun classify(statusCode: Int): Result = when {
            statusCode in 200..299 -> Result.VALID
            statusCode == 400 || statusCode == 401 || statusCode == 403 -> Result.INVALID
            // The server has authenticated the request before applying its quota.
            statusCode == 429 -> Result.VALID
            else -> Result.UNAVAILABLE
        }
    }
}
