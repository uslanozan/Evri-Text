package tr.com.uslanozan.evritext.translate

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TranslationFailureTest {
    @Test fun `Gemini stops immediately on daily quota instead of burning retry attempts`() = runBlocking {
        var calls = 0
        val body = """{"error":{"details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                {"quotaId":"GenerateContentInputTokensPerModelPerDay-FreeTier","description":"daily quota exceeded"}
            ]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"78428s"}
        ]}}"""
        val http = fakeHttp(body, code = 429) { calls += 1 }
        val provider = GeminiTranslationProvider("test-key", maxAttempts = 3, http = http)

        val error = assertThrows(NonRetryableProviderException::class.java) {
            runBlocking {
                provider.translate(listOf("Hello"), "en", "tr", TranslationContext())
            }
        }

        assertEquals(1, calls)
        assertTrue(error.message.orEmpty().contains("daily quota"))
    }

    @Test fun `Gemini rejects partial JSON instead of filling gaps with source text`() {
        val http = fakeHttp(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"i\":0,\"tr\":\"Merhaba\"}]"}]}}]}""",
        )
        val provider = GeminiTranslationProvider("test-key", maxAttempts = 1, http = http)

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                provider.translate(listOf("Hello", "World"), "en", "tr", TranslationContext())
            }
        }
        assertTrue(error.message.orEmpty().contains("incomplete translation"))
    }

    @Test fun `OpenAI compatible providers also reject incomplete batches`() {
        val provider = OpenAiCompatibleTranslationProvider.openAi(
            apiKey = "test-key",
            model = "test-model",
            http = fakeHttp(
                """{"choices":[{"message":{"content":"[{\"i\":0,\"tr\":\"Merhaba\"}]"}}]}""",
            ),
            maxAttempts = 1,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                provider.translate(listOf("Hello", "World"), "en", "tr", TranslationContext())
            }
        }
        assertTrue(error.message.orEmpty().contains("incomplete translation"))
    }

    @Test fun `cancelling a translation also cancels its in-flight HTTP call`() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val requestCancelled = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requestStarted.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(10)
            requestCancelled.countDown()
            throw IOException("cancelled by test")
        }.build()
        val provider = OpenAiCompatibleTranslationProvider.openAi(
            apiKey = "test-key",
            model = "test-model",
            http = http,
        )

        val job = launch(Dispatchers.Default) {
            provider.translate(listOf("Hello"), "en", "tr", TranslationContext())
        }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue("OkHttp call stayed active after coroutine cancellation", requestCancelled.await(5, TimeUnit.SECONDS))
    }

    private fun fakeHttp(
        body: String,
        code: Int = 200,
        onCall: () -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        onCall()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Too Many Requests")
            .body(body.toResponseBody())
            .build()
    }).build()
}
