package tr.com.uslanozan.evritext.subtitles

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tr.com.uslanozan.evritext.captions.CaptionSource
import tr.com.uslanozan.evritext.captions.CaptionRepository
import tr.com.uslanozan.evritext.translate.PROMPT_VERSION
import tr.com.uslanozan.evritext.translate.NonRetryableProviderException
import tr.com.uslanozan.evritext.translate.TranslationContext
import tr.com.uslanozan.evritext.translate.TranslationProvider
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Caption fetch → sentence merge → translation → timed cues.
 *
 * The one thing this does that Phase 0's pipeline did not is **translate in the order
 * the viewer needs**. Phase 0 waited for the whole film before showing anything, which
 * measured 89 seconds of blank screen after pressing play — unusable. Here the chunk
 * nearest the current playhead goes first and every finished chunk is published
 * immediately. Priorities are recalculated after each response, so seeking does not
 * leave the worker translating toward the old position.
 */
class SubtitleEngine(
    private val provider: TranslationProvider,
    private val cacheDir: File,
    private val captions: CaptionRepository = CaptionSource(),
    private val targetLang: String = "tr",
    private val chunkSize: Int = 30,
    private val contextSize: Int = 3,
) {

    sealed interface Result {
        /** The video's audio is already in the target language: do nothing. */
        data class AlreadySpoken(val languageTag: String) : Result

        /** A human already wrote subtitles in the target language: use those. */
        data class AlreadySubtitled(val languageTag: String) : Result
        data class NoCaptions(val videoId: String) : Result
        data class Ready(
            val cues: List<Cue>,
            val fromCache: Boolean,
            val warning: String? = null,
            val retryable: Boolean = true,
        ) : Result
        data class Failed(val reason: String, val retryable: Boolean = true) : Result
    }

    @Serializable
    private data class CachedCue(val startMs: Long, val endMs: Long, val text: String)

    @Serializable
    private data class CachedTranslation(val index: Int, val text: String)

    /**
     * @param startPositionMs where the viewer is now, so that part is translated first
     * @param onPartial called with the cues available so far, as each chunk lands
     */
    suspend fun build(
        videoId: String,
        startPositionMs: Long,
        currentPositionMs: () -> Long = { startPositionMs },
        onPartial: (List<Cue>) -> Unit,
    ): Result {
        cachedCues(videoId)?.let { return Result.Ready(it, fromCache = true) }

        val info = try {
            captions.probe(videoId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.Failed("caption probe failed: ${error.message}")
        }

        info.existingTargetIn(targetLang)?.let { existing ->
            return when (existing.kind) {
                CaptionSource.VideoCaptions.ExistingTargetKind.SPOKEN ->
                    Result.AlreadySpoken(existing.track.languageTag)
                CaptionSource.VideoCaptions.ExistingTargetKind.SUBTITLED ->
                    Result.AlreadySubtitled(existing.track.languageTag)
            }
        }

        val track = info.best() ?: return Result.NoCaptions(videoId)

        val vtt = try {
            captions.download(track)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.Failed(
                "caption download failed: ${error.message}",
                retryable = !error.message.orEmpty().isPermanentFailure(),
            )
        }

        val sentences = Sentences.merge(Vtt.load(vtt))
        if (sentences.isEmpty()) return Result.NoCaptions(videoId)

        Log.i(TAG, "$videoId: ${sentences.size} sentences from ${track.languageTag}")

        val chunks = sentences.indices.step(chunkSize).map { start ->
            start until minOf(start + chunkSize, sentences.size)
        }
        val translated = arrayOfNulls<String>(sentences.size)
        val completedTranslations = arrayOfNulls<String>(sentences.size)
        val failedChunks = ConcurrentLinkedQueue<String>()
        var hasRetryableFailure = false
        val cachedTranslations = cachedTranslations(videoId, sentences.size)
        cachedTranslations.forEach { (index, text) ->
            translated[index] = text
            completedTranslations[index] = text
        }
        if (cachedTranslations.isNotEmpty()) onPartial(collect(sentences, translated))

        val pending = chunks.filterTo(mutableListOf()) { range ->
            translated.sliceArray(range).any { it == null }
        }
        val pendingCount = pending.size
        while (pending.isNotEmpty()) {
            // Recheck the live playhead between requests. A seek during a slow API
            // call must redirect the next request toward the new position.
            val position = runCatching { currentPositionMs() }.getOrDefault(startPositionMs)
            val range = pending.minBy { distanceToChunk(sentences, it, position) }
            pending.remove(range)
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
            try {
                val result = provider.translate(
                    slice.map { it.text },
                    track.baseLanguage,
                    targetLang,
                    context,
                )
                require(result.size == slice.size && result.none(String::isBlank)) {
                    "provider returned ${result.count(String::isNotBlank)}/${slice.size} translations"
                }
                result.forEachIndexed { i, text ->
                    translated[range.first + i] = text
                    completedTranslations[range.first + i] = text
                }
                writeProgress(videoId, completedTranslations)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "chunk ${range.first} failed: ${error.message}")
                failedChunks.add(error.message ?: error.javaClass.simpleName)
                if (error !is NonRetryableProviderException && !error.message.orEmpty().isPermanentFailure()) {
                    hasRetryableFailure = true
                }
                slice.forEachIndexed { i, sentence -> translated[range.first + i] = sentence.text }
            }
            onPartial(collect(sentences, translated))
        }

        val cues = collect(sentences, translated)
        if (failedChunks.size == pendingCount && pendingCount > 0) {
            return Result.Failed(
                "translation failed: ${failedChunks.first()}",
                retryable = hasRetryableFailure,
            )
        }
        if (failedChunks.isEmpty()) {
            writeCache(videoId, cues)
            withContext(NonCancellable + Dispatchers.IO) { progressFile(videoId).delete() }
        }
        return Result.Ready(
            cues,
            fromCache = false,
            warning = failedChunks.peek(),
            retryable = hasRetryableFailure,
        )
    }

    /** Only the sentences translated so far, in order — the rest simply are not shown. */
    private fun collect(sentences: List<Sentence>, translated: Array<String?>): List<Cue> =
        sentences.indices.flatMap { index ->
            translated[index]?.takeIf { it.isNotBlank() }?.let {
                TargetCueSegmenter.segment(sentences[index], it)
            }.orEmpty()
        }

    // ------------------------------------------------------------------ cache

    /**
     * Translation and segmentation versions are both part of the key: changing either
     * must not serve old, differently rendered subtitles.
     */
    private fun cacheFile(videoId: String) =
        File(cacheDir, "$videoId.$targetLang.${provider.id}.$PROMPT_VERSION.$SEGMENTATION_VERSION.json")

    private fun progressFile(videoId: String) =
        File(cacheDir, "$videoId.$targetLang.${provider.id}.$PROMPT_VERSION.$SEGMENTATION_VERSION.progress.json")

    private fun cachedTranslations(videoId: String, sentenceCount: Int): List<CachedTranslation> {
        val file = progressFile(videoId)
        if (!file.isFile) return emptyList()
        return runCatching {
            JSON.decodeFromString(TRANSLATION_SERIALIZER, file.readText())
                .filter { it.index in 0 until sentenceCount && it.text.isNotBlank() }
                .distinctBy(CachedTranslation::index)
        }.onFailure { Log.w(TAG, "unreadable progress ${file.name}: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private suspend fun writeProgress(videoId: String, translated: Array<String?>) =
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                cacheDir.mkdirs()
                progressFile(videoId).writeText(
                    JSON.encodeToString(
                        TRANSLATION_SERIALIZER,
                        translated.mapIndexedNotNull { index, text ->
                            text?.takeIf(String::isNotBlank)?.let { CachedTranslation(index, it) }
                        },
                    ),
                )
            }.onFailure { Log.w(TAG, "progress write failed: ${it.message}") }
        }

    private fun distanceToChunk(sentences: List<Sentence>, range: IntRange, position: Long): Long {
        val first = sentences[range.first].startMs
        val last = sentences[range.last].endMs
        return when {
            position in first..last -> 0L
            position < first -> first - position
            else -> position - last
        }
    }

    private fun String.isPermanentFailure(): Boolean {
        val message = lowercase()
        return "http 429: daily quota" in message || "daily quota" in message ||
            "http 401" in message || "http 403" in message ||
            "api_key_invalid" in message || "permission_denied" in message
    }

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
        private const val SEGMENTATION_VERSION = "seg4"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val CACHE_SERIALIZER =
            kotlinx.serialization.builtins.ListSerializer(CachedCue.serializer())
        private val TRANSLATION_SERIALIZER =
            kotlinx.serialization.builtins.ListSerializer(CachedTranslation.serializer())
    }
}
