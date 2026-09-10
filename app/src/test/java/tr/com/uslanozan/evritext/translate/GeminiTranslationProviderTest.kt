package tr.com.uslanozan.evritext.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tr.com.uslanozan.evritext.captions.CaptionSource
import tr.com.uslanozan.evritext.subtitles.Sentences
import tr.com.uslanozan.evritext.subtitles.Vtt
import java.io.File

/**
 * Runs the real prompt against the real API, on real ASR text.
 *
 * The point is not that "translation happens" but that the failure modes Phase 0
 * found stay fixed: the reply has to come back keyed on `tr`, one item per input,
 * with nothing dropped.
 */
class GeminiTranslationProviderTest {

    @Test
    fun `translates ASR sentences one to one`() = runBlocking {
        val apiKey = findApiKey()
        assumeTrue("no GEMINI_API_KEY environment variable or root .env — skipping", apiKey != null)

        val source = CaptionSource()
        val track = requireNotNull(source.probe(BIG_STAN).best())
        val sentences = Sentences.merge(Vtt.load(source.download(track))).take(30)

        val provider = GeminiTranslationProvider(apiKey!!)
        val translations = provider.translate(
            sentences = sentences.map { it.text },
            sourceLang = "en",
            targetLang = "tr",
            context = TranslationContext(videoTitle = "Big Stan"),
        )

        val displayCues = Sentences.toCues(sentences, translations)
        println("${sentences.size} translated sentences -> ${displayCues.size} display cues")
        displayCues.take(12).forEach {
            println("[${Vtt.formatTimestamp(it.startMs)}] ${it.text}")
        }

        assertEquals("lost or gained items", sentences.size, translations.size)
        assertTrue("some translations are empty", translations.none { it.isBlank() })
        assertTrue(
            "output looks untranslated — is it echoing the source?",
            translations.count { it in sentences.map { s -> s.text } } < sentences.size / 4,
        )
    }

    @Test
    fun `translates French and Arabic into Turkish`() = runBlocking {
        val apiKey = findApiKey()
        assumeTrue("no GEMINI_API_KEY environment variable or root .env — skipping", apiKey != null)
        val provider = GeminiTranslationProvider(apiKey!!)
        val samples = listOf(
            "fr" to "Je ne pensais pas que ce projet fonctionnerait aussi bien sur la télévision.",
            "ar" to "لم أتوقع أن يعمل هذا المشروع بهذه السلاسة على التلفاز.",
        )

        samples.forEach { (language, source) ->
            val translated = provider.translate(
                sentences = listOf(source),
                sourceLang = language,
                targetLang = "tr",
                context = TranslationContext(videoTitle = "Evri Text language test"),
            ).single()

            println("$language -> tr: $translated")
            assertTrue("$language translation is empty", translated.isNotBlank())
            assertTrue("$language text was echoed without translation", translated != source)
        }
    }

    private fun findApiKey(): String? {
        System.getenv("GEMINI_API_KEY")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        var dir: File? = File("").absoluteFile
        repeat(5) {
            val env = File(dir, ".env")
            if (env.exists()) {
                return env.readLines()
                    .firstOrNull { it.startsWith("GEMINI_API_KEY=") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            dir = dir?.parentFile
        }
        return null
    }

    private companion object {
        const val BIG_STAN = "sx8pViXxZQg"
    }
}
