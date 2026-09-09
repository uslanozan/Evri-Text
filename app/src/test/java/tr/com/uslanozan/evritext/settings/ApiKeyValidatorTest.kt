package tr.com.uslanozan.evritext.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeyValidatorTest {

    @Test
    fun `accepts successful and quota limited authenticated requests`() {
        assertEquals(
            ApiKeyValidator.Result.VALID,
            ApiKeyValidator.classify(200),
        )
        assertEquals(
            ApiKeyValidator.Result.VALID,
            ApiKeyValidator.classify(429),
        )
    }

    @Test
    fun `rejects authentication failures`() {
        listOf(400, 401, 403).forEach { status ->
            assertEquals(
                ApiKeyValidator.Result.INVALID,
                ApiKeyValidator.classify(status),
            )
        }
    }

    @Test
    fun `treats server and unexpected responses as temporary failures`() {
        listOf(404, 500, 503).forEach { status ->
            assertEquals(
                ApiKeyValidator.Result.UNAVAILABLE,
                ApiKeyValidator.classify(status),
            )
        }
    }

    @Test
    fun `uses each provider's documented authentication header`() {
        val key = "secret"
        val gemini = ApiKeyValidator.request(
            tr.com.uslanozan.evritext.translate.LlmProvider.GEMINI,
            key,
        )
        assertEquals(key, gemini.header("x-goog-api-key"))

        listOf(
            tr.com.uslanozan.evritext.translate.LlmProvider.OPENAI,
            tr.com.uslanozan.evritext.translate.LlmProvider.OPENROUTER,
            tr.com.uslanozan.evritext.translate.LlmProvider.GROQ,
        ).forEach { provider ->
            assertEquals("Bearer $key", ApiKeyValidator.request(provider, key).header("Authorization"))
        }

        val anthropic = ApiKeyValidator.request(
            tr.com.uslanozan.evritext.translate.LlmProvider.ANTHROPIC,
            key,
        )
        assertEquals(key, anthropic.header("x-api-key"))
        assertEquals("2023-06-01", anthropic.header("anthropic-version"))
    }
}
