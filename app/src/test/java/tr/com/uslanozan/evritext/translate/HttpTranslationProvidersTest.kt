package tr.com.uslanozan.evritext.translate

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTranslationProvidersTest {

    @Test
    fun `OpenAI compatible provider sends auth and parses indexed translations`() = runBlocking {
        var captured: Request? = null
        val http = fakeHttp(
            """{"choices":[{"message":{"content":"[{\"i\":0,\"tr\":\"Merhaba\"}]"}}]}""",
        ) { captured = it }
        val provider = OpenAiCompatibleTranslationProvider.openAi("openai-key", "gpt-test", http)

        val translated = provider.translate(
            listOf("Hello"),
            "en",
            "tr",
            TranslationContext(videoTitle = "Test video"),
        )

        assertEquals(listOf("Merhaba"), translated)
        assertEquals("Bearer openai-key", captured?.header("Authorization"))
        val body = captured?.body?.let { requestBody ->
            okio.Buffer().also(requestBody::writeTo).readUtf8()
        }.orEmpty()
        assertTrue(body.contains("gpt-test"))
        assertTrue(body.contains("Test video"))
        assertTrue(body.contains("detect it").not())
    }

    @Test
    fun `Anthropic provider uses Messages headers and joins text blocks`() = runBlocking {
        var captured: Request? = null
        val http = fakeHttp(
            """{"content":[{"type":"text","text":"[{\"i\":0,\"tr\":\"Dünya\"}]"}]}""",
        ) { captured = it }
        val provider = AnthropicTranslationProvider("anthropic-key", "claude-test", http = http)

        val translated = provider.translate(
            listOf("World"),
            sourceLang = null,
            targetLang = "tr",
            context = TranslationContext(),
        )

        assertEquals(listOf("Dünya"), translated)
        assertEquals("anthropic-key", captured?.header("x-api-key"))
        assertEquals("2023-06-01", captured?.header("anthropic-version"))
        assertEquals("/v1/messages", captured?.url?.encodedPath)
    }

    private fun fakeHttp(json: String, capture: (Request) -> Unit): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            capture(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody())
                .build()
        }).build()
}
