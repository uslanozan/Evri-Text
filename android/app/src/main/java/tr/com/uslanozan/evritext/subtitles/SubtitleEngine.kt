package tr.com.uslanozan.evritext.subtitles

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tr.com.uslanozan.evritext.captions.CaptionSource
import tr.com.uslanozan.evritext.translate.PROMPT_VERSION
import tr.com.uslanozan.evritext.translate.TranslationContext
import tr.com.uslanozan.evritext.translate.TranslationProvider
import java.io.File
import kotlin.math.abs

/**
 * Caption fetch → sentence merge → translation → timed cues.
 *
 * The one thing this does that Phase 0's pipeline did not is **translate in the order
 * the viewer needs**. Phase 0 waited for the whole film before showing anything, which
 * measured 89 seconds of blank screen after pressing play — unusable. Here the chunk
 * covering the current playhead goes first and every finished chunk is published
 * immediately, so subtitles appear in roughly one chunk's latency (~3 s).
 */
class SubtitleEngine(
    private val provider: TranslationProvider,
    private val cacheDir: File,
    private val captions: CaptionSource = CaptionSource(),
    private val targetLang: String = "tr",
    private val chunkSize: Int = 30,
    private val contextSize: Int = 3,
    private val workers: Int = 3,
) {

    sealed interface Result {
        /** A human already wrote subtitles in the target language: do nothing. */
        data class AlreadySubtitled(val languageTag: String) : Result
        data class NoCaptions(val videoId: String) : Result
        data class Ready(val cues: List<Cue>, val fromCache: Boolean) : Result
        data class Failed(val reason: String) : Result
    }

    @Serializable
    private data class CachedCue(val startMs: Long, val endMs: Long, val text: String)

    /**
     * @param startPositionMs where the viewer is now, so that part is translated first
     * @param onPartial called with the cues available so far, as each chunk lands
     */
    suspend fun build(
        videoId: String,
        startPositionMs: Long,
        onPartial: (List<Cue>) -> Unit,
    ): Result {
        cachedCues(videoId)?.let { return Result.Ready(it, fromCache = true) }

        val info = runCatching { captions.probe(videoId) }
            .getOrElse { return Result.Failed("caption probe failed: ${it.message}") }

        info.manualIn(targetLang)?.let { return Result.AlreadySubtitled(it.languageTag) }
        val track = info.best() ?: return Result.NoCaptions(videoId)

        val vtt = runCatching { captions.download(track) }
            .getOrElse { return Result.Failed("caption download failed: ${it.message}") }

        val sentences = Sentences.merge(Vtt.load(vtt))
        if (sentences.isEmpty()) return Result.NoCaptions(videoId)

        Log.i(TAG, "$videoId: ${sentences.size} sentences from ${track.languageTag}")

        val chunks = sentences.indices.step(chunkSize).map { start ->
            start until minOf(start + chunkSize, sentences.size)
        }
        // Nearest-first: the viewer is watching *now*, not at the start of the file.
        val ordered = chunks.sortedBy { range ->
            abs(sentences[range.first].startMs - startPositionMs)
        }

        val translated = arrayOfNulls<String>(sentences.size)
        val gate = Semaphore(workers)

        coroutineScope {
            ordered.map { range ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val slice = sentences.subList(range.first, range.last + 1)
                        val context = TranslationContext(
                            videoTitle = info.title,
                            before = sentences.subList(
                                maxOf(0, range.first - contextSize),
                                range.first,
                            ).map { it.text },
                            after = sentences.subList(
                                minOf(range.last + 1, sentences.size),
                                minOf(range.last + 1 + contextSize, sentences.size),
                            ).map { it.text },
                        )
                        val result = runCatching {
                            provider.translate(
                                slice.map { it.text },
                                track.baseLanguage,
                                targetLang,
                                context,
                            )
                        }.getOrElse { error ->
                            // One bad chunk must not sink the video: fall back to the
                            // source text so the viewer still sees *something* there.
                            Log.e(TAG, "chunk ${range.first} failed: ${error.message}")
                            slice.map { it.text }
                        }
                        result.forEachIndexed { i, text -> translated[range.first + i] = text }
                        onPartial(collect(sentences, translated))
                    }
                }
            }.awaitAll()
        }

        val cues = collect(sentences, translated)
        if (translated.all { it != null }) writeCache(videoId, cues)
        return Result.Ready(cues, fromCache = false)
    }

    /** Only the sentences translated so far, in order — the rest simply are not shown. */
    private fun collect(sentences: List<Sentence>, translated: Array<String?>): List<Cue> =
        sentences.indices.mapNotNull { index ->
            translated[index]?.takeIf { it.isNotBlank() }?.let {
                Cue(sentences[index].startMs, sentences[index].endMs, it.trim())
            }
        }

    // ------------------------------------------------------------------ cache

    /**
     * Key is `videoId + lang + provider + promptVersion`, the same scheme Phase 0 used
     * (DESIGN.md section 6): changing the prompt must not serve old translations.
     */
    private fun cacheFile(videoId: String) =
        File(cacheDir, "$videoId.$targetLang.${provider.id}.$PROMPT_VERSION.json")

    private fun cachedCues(videoId: String): List<Cue>? {
        val file = cacheFile(videoId)
        if (!file.exists()) return null
        return runCatching {
            JSON.decodeFromString(CACHE_SERIALIZER, file.readText())
                .map { Cue(it.startMs, it.endMs, it.text) }
        }.onFailure { Log.w(TAG, "unreadable cache ${file.name}: ${it.message}") }.getOrNull()
    }

    private suspend fun writeCache(videoId: String, cues: List<Cue>) = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile(videoId).writeText(
                JSON.encodeToString(
                    CACHE_SERIALIZER,
                    cues.map { CachedCue(it.startMs, it.endMs, it.text) },
                ),
            )
        }.onFailure { Log.w(TAG, "cache write failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "SubtitleEngine"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val CACHE_SERIALIZER =
            kotlinx.serialization.builtins.ListSerializer(CachedCue.serializer())
    }
}
