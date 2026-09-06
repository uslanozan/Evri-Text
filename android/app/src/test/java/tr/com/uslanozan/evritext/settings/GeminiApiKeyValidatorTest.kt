package tr.com.uslanozan.evritext.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiApiKeyValidatorTest {

    @Test
    fun `accepts successful and quota limited authenticated requests`() {
        assertEquals(
            GeminiApiKeyValidator.Result.VALID,
            GeminiApiKeyValidator.classify(200),
        )
        assertEquals(
            GeminiApiKeyValidator.Result.VALID,
            GeminiApiKeyValidator.classify(429),
        )
    }

    @Test
    fun `rejects authentication failures`() {
        listOf(400, 401, 403).forEach { status ->
            assertEquals(
                GeminiApiKeyValidator.Result.INVALID,
                GeminiApiKeyValidator.classify(status),
            )
        }
    }

    @Test
    fun `treats server and unexpected responses as temporary failures`() {
        listOf(404, 500, 503).forEach { status ->
            assertEquals(
                GeminiApiKeyValidator.Result.UNAVAILABLE,
                GeminiApiKeyValidator.classify(status),
            )
        }
    }
}
