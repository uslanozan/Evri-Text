package tr.com.uslanozan.evritext.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorTest {
    @Test fun `detects daily and per-minute Gemini quotas from the full structured response`() {
        val details = parseGeminiQuotaDetails(
            """{"error":{"details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                    {"quotaId":"GenerateContentInputTokensPerModelPerDay-FreeTier","description":"daily quota exceeded"}
                ]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"78428s"}
            ]}}""",
        )
        assertTrue(details.dailyLimitExceeded)
        assertEquals(78_428_000L, details.retryDelayMs)

        val minuteLimit = parseGeminiQuotaDetails(
            """{"error":{"details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                    {"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier","description":"per minute limit"}
                ]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"24s"}
            ]}}""",
        )
        assertFalse(minuteLimit.dailyLimitExceeded)
        assertEquals(24_000L, minuteLimit.retryDelayMs)
    }

    @Test fun `ignores malformed provider error bodies`() {
        assertEquals(GeminiQuotaDetails(false, null), parseGeminiQuotaDetails("not json"))
    }
}
