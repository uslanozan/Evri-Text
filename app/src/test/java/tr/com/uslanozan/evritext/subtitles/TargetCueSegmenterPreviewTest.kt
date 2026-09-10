package tr.com.uslanozan.evritext.subtitles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tr.com.uslanozan.evritext.captions.CaptionSource
import tr.com.uslanozan.evritext.translate.GeminiTranslationProvider
import tr.com.uslanozan.evritext.translate.TranslationContext
import java.io.File
import kotlin.math.abs

/** Manual, TV-free preview of target-language cue segmentation on a full film. */
class TargetCueSegmenterPreviewTest {

    @Test
    fun `prints old and new cue timing across Big Stan`() = runBlocking {
        assumeTrue(
            "manual preview — run with -PsegmentationPreview=true",
            System.getProperty("evritext.segmentationPreview") == "true",
        )
        val apiKey = findApiKey()
        assumeTrue("no GEMINI_API_KEY or root .env — skipping", apiKey != null)

        val source = CaptionSource()
        val info = source.probe(BIG_STAN)
        val track = requireNotNull(info.best())
        val all = Sentences.merge(Vtt.load(source.download(track)))
        val samples = SAMPLE_MINUTES.map { minute ->
            all.asSequence()
                .filter { abs(it.startMs - minute * 60_000L) <= SEARCH_RADIUS_MS }
                .filter { it.endMs - it.startMs >= 3_000 && it.sourceCues.size >= 2 }
                .maxBy { sentence ->
                    sentence.text.length + sentence.sourceCues.size * 8
                }
        }

        val translations = GeminiTranslationProvider(apiKey!!).translate(
            sentences = samples.map(Sentence::text),
            sourceLang = track.baseLanguage,
            targetLang = "tr",
            context = TranslationContext(videoTitle = info.title),
        )
        assertEquals(samples.size, translations.size)

        var splitCount = 0
        samples.zip(translations).forEach { (sentence, translation) ->
            val segmented = TargetCueSegmenter.segment(sentence, translation)
            if (segmented.size > 1) splitCount += 1
            println("\n--- ${clock(sentence.startMs)} ---")
            println("OLD ${range(sentence.startMs, sentence.endMs)} $translation")
            segmented.forEach { cue -> println("NEW ${range(cue.startMs, cue.endMs)} ${cue.text}") }
        }

        println("\n${samples.size} film sections; $splitCount were resegmented")
        assertTrue("preview produced no usable cues", translations.none(String::isBlank))
    }

    private fun range(startMs: Long, endMs: Long) = "[${clock(startMs)}–${clock(endMs)}]"

    private fun clock(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun findApiKey(): String? {
        System.getenv("GEMINI_API_KEY")?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        var dir: File? = File("").absoluteFile
        repeat(5) {
            File(dir, ".env").takeIf(File::exists)?.readLines()
                ?.firstOrNull { it.startsWith("GEMINI_API_KEY=") }
                ?.substringAfter('=')?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
            dir = dir?.parentFile
        }
        return null
    }

    private companion object {
        const val BIG_STAN = "sx8pViXxZQg"
        const val SEARCH_RADIUS_MS = 90_000L
        val SAMPLE_MINUTES = listOf(1, 10, 20, 30, 40, 50, 60, 70, 80, 90)
    }
}
