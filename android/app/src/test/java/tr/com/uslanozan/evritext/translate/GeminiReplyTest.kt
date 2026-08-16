package tr.com.uslanozan.evritext.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here is a reply shape that actually cost Phase 0 a chunk of a film.
 *
 * The failure mode is what makes these worth pinning: a rejected reply does not
 * crash, it falls back to the untranslated English, so the symptom is a viewer
 * hitting a patch of the film they cannot read.
 */
class GeminiReplyTest {

    @Test
    fun `reads the documented shape`() {
        val reply = """[{"i": 0, "tr": "merhaba"}, {"i": 1, "tr": "dünya"}]"""
        assertEquals(listOf("merhaba", "dünya"), GeminiReply.parseIndexed(reply, 2))
    }

    @Test
    fun `accepts the key the model mirrors back from the input`() {
        // Naming the input field "text" made the model answer with "text" instead of
        // "tr", and every line came out blank. The input key is "source" now, but the
        // parser stays forgiving.
        val reply = """[{"i": 0, "text": "merhaba"}, {"i": 1, "translation": "dünya"}]"""
        assertEquals(listOf("merhaba", "dünya"), GeminiReply.parseIndexed(reply, 2))
    }

    @Test
    fun `ignores content appended after the array`() {
        // "Extra data" from a strict parse used to discard the whole chunk.
        val reply = """
            [{"i": 0, "tr": "merhaba"}]
            Note: I translated these idiomatically.
        """.trimIndent()
        assertEquals(listOf("merhaba"), GeminiReply.parseIndexed(reply, 1))
    }

    @Test
    fun `unwraps a markdown fence`() {
        val reply = "```json\n[{\"i\": 0, \"tr\": \"merhaba\"}]\n```"
        assertEquals(listOf("merhaba"), GeminiReply.parseIndexed(reply, 1))
    }

    @Test
    fun `unwraps an object that carries the array`() {
        val reply = """{"items": [{"i": 0, "tr": "merhaba"}]}"""
        assertEquals(listOf("merhaba"), GeminiReply.parseIndexed(reply, 1))
    }

    @Test
    fun `keeps what came back when an item is missing`() {
        // Dropping one index out of thirty must not cost the other twenty-nine their
        // translation — the caller fills the null from the source text.
        val reply = """[{"i": 0, "tr": "bir"}, {"i": 2, "tr": "üç"}]"""
        val parsed = GeminiReply.parseIndexed(reply, 3)

        assertEquals("bir", parsed[0])
        assertNull(parsed[1])
        assertEquals("üç", parsed[2])
    }

    @Test
    fun `ignores an index outside the chunk`() {
        val reply = """[{"i": 0, "tr": "bir"}, {"i": 99, "tr": "nereden geldi"}]"""
        assertEquals(listOf("bir"), GeminiReply.parseIndexed(reply, 1))
    }

    @Test
    fun `brackets inside a translation do not end the scan early`() {
        val reply = """[{"i": 0, "tr": "o \"kapa\" dedi ] falan"}]"""
        assertEquals(listOf("o \"kapa\" dedi ] falan"), GeminiReply.parseIndexed(reply, 1))
    }

    @Test
    fun `refuses a reply with nothing usable in it`() {
        val failures = listOf(
            "",
            "I'm sorry, I can't help with that.",
            "[]",
            """[{"i": 0}]""",
        )
        failures.forEach { reply ->
            val threw = runCatching { GeminiReply.parseIndexed(reply, 1) }.isFailure
            assertTrue("should have rejected: $reply", threw)
        }
    }

    @Test
    fun `429 waits as long as the server asked`() {
        val provider = GeminiTranslationProvider(apiKey = "unused")
        val quotaError =
            "429 RESOURCE_EXHAUSTED {'retryDelay': '24s'} quota exceeded"

        // Server said 24s, plus a second of margin.
        assertEquals(25_000L, provider.retryDelayMs(quotaError, attempt = 1))
        // Anything else backs off gently instead of hammering.
        assertEquals(2_000L, provider.retryDelayMs("connection reset", attempt = 1))
        assertEquals(10_000L, provider.retryDelayMs("connection reset", attempt = 9))
    }
}
