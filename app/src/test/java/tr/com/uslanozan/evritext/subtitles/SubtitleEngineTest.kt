package tr.com.uslanozan.evritext.subtitles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tr.com.uslanozan.evritext.captions.CaptionRepository
import tr.com.uslanozan.evritext.captions.CaptionSource
import tr.com.uslanozan.evritext.translate.NonRetryableProviderException
import tr.com.uslanozan.evritext.translate.TranslationContext
import tr.com.uslanozan.evritext.translate.TranslationProvider
import java.io.File
import java.nio.file.Files

class SubtitleEngineTest {
    private lateinit var cacheDir: File
    private lateinit var captions: FakeCaptions

    @Before fun setUp() {
        cacheDir = Files.createTempDirectory("evritext-subtitle-test-").toFile()
        captions = FakeCaptions()
    }

    @After fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test fun `prioritizes the chunk containing the current playback position`() = runBlocking {
        var playheadMs = 0L
        val requested = mutableListOf<Int>()
        val provider = provider { lines ->
            lines.map { text -> text.sentenceIndex().also(requested::add).let { "tr-$it" } }
                .also { playheadMs = captions.sentenceStart(8) }
        }
        val result = engine(provider, chunkSize = 2).build(
            "seek-test",
            startPositionMs = 0,
            currentPositionMs = { playheadMs },
        ) {}

        assertTrue(result is SubtitleEngine.Result.Ready)
        assertEquals(listOf(0, 1, 8, 9), requested.take(4))
    }

    @Test fun `resumes from persisted successful chunks without requesting them again`() = runBlocking {
        val secondRequestStarted = CompletableDeferred<Unit>()
        val requestedBeforePause = mutableListOf<Int>()
        var callCount = 0
        val blockingProvider = provider { lines ->
            callCount += 1
            val indices = lines.map { it.sentenceIndex() }
            requestedBeforePause += indices
            if (callCount == 2) {
                secondRequestStarted.complete(Unit)
                awaitCancellation()
            }
            indices.map { "tr-$it" }
        }
        val firstBuild = launch(Dispatchers.Default) {
            engine(blockingProvider, chunkSize = 2).build("resume-test", 0) {}
        }
        secondRequestStarted.await()
        firstBuild.cancelAndJoin()

        val progress = cacheDir.listFiles().orEmpty().firstOrNull { it.name.endsWith(".progress.json") }
        assertNotNull("completed chunk was not saved", progress)
        assertEquals(listOf(0, 1), readProgressIndices(requireNotNull(progress)))

        val requestedAfterResume = mutableListOf<Int>()
        val resumedProvider = provider { lines ->
            lines.map { text -> text.sentenceIndex().also(requestedAfterResume::add).let { "tr-$it" } }
        }
        val result = engine(resumedProvider, chunkSize = 2).build("resume-test", 0) {}

        assertTrue(result is SubtitleEngine.Result.Ready)
        assertEquals((2..11).toList(), requestedAfterResume)
        assertEquals(12, (result as SubtitleEngine.Result.Ready).cues.size)
        assertFalse(requireNotNull(progress).exists())
    }

    @Test fun `rejects partial provider results and leaves them out of the completed cache`() = runBlocking {
        val partialProvider = provider { listOf("only-one") }
        val partialResult = engine(partialProvider, chunkSize = 2).build("partial-test", 0) {}

        assertTrue(partialResult is SubtitleEngine.Result.Failed)
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.endsWith(".json") })

        val completeProvider = provider { lines -> lines.map { "Turkish ${it.sentenceIndex()}" } }
        val completeResult = engine(completeProvider, chunkSize = 2).build("partial-test", 0) {}
        assertTrue(completeResult is SubtitleEngine.Result.Ready)
        assertTrue((completeResult as SubtitleEngine.Result.Ready).cues.all { it.text.startsWith("Turkish") })
    }

    @Test fun `does not schedule automatic retries for a non-retryable quota response`() = runBlocking {
        val provider = provider { throw NonRetryableProviderException("HTTP 429: quota retry delay exceeds safe retry window") }

        val result = engine(provider, chunkSize = 2).build("quota-test", 0) {}

        assertTrue(result is SubtitleEngine.Result.Failed)
        assertFalse((result as SubtitleEngine.Result.Failed).retryable)
    }

    private fun engine(provider: TranslationProvider, chunkSize: Int) = SubtitleEngine(
        provider = provider,
        cacheDir = cacheDir,
        captions = captions,
        chunkSize = chunkSize,
        contextSize = 0,
    )

    private fun provider(translateLines: suspend (List<String>) -> List<String>) =
        object : TranslationProvider {
            override val id = "test-model"
            override val displayName = "Test model"
            override suspend fun translate(
                sentences: List<String>,
                sourceLang: String?,
                targetLang: String,
                context: TranslationContext,
            ) = translateLines(sentences)
        }

    private fun readProgressIndices(file: File): List<Int> = Regex(""""index":(\d+)""")
        .findAll(file.readText())
        .map { it.groupValues[1].toInt() }
        .toList()

    private fun String.sentenceIndex(): Int = substringAfter("Sentence ").substringBefore(' ').toInt()

    private class FakeCaptions : CaptionRepository {
        private val texts = (0 until 12).map { "Sentence $it is here." }

        override suspend fun probe(videoId: String) = CaptionSource.VideoCaptions(
            videoId = videoId,
            title = "Fixture video",
            durationS = 24.0,
            tracks = listOf(
                CaptionSource.Track("en", "English", "fixture", true, "vtt"),
            ),
            spokenLanguageTag = "en",
        )

        override suspend fun download(track: CaptionSource.Track) = buildString {
            appendLine("WEBVTT")
            appendLine()
            texts.forEachIndexed { index, text ->
                val start = index * 2_000L
                appendLine("${Vtt.formatTimestamp(start, '.')} --> ${Vtt.formatTimestamp(start + 1_500, '.')}")
                appendLine(text)
                appendLine()
            }
        }

        fun sentenceStart(index: Int) = index * 2_000L
    }
}
