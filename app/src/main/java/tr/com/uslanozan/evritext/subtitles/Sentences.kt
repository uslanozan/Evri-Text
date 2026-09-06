package tr.com.uslanozan.evritext.subtitles

/**
 * Merge fragmented ASR cues into whole sentences, following the prototype's measured rules.
 *
 * This is the step that makes the difference between our output and YouTube's own
 * Turkish auto-translation (DESIGN.md section 4): translating two-second fragments
 * produces nonsense, translating sentences does not.
 *
 * ASR captions frequently carry no punctuation at all, so the full stop is *not* the
 * boundary that does the work in practice — the character and duration caps are.
 */
data class Sentence(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val cueCount: Int,
)

object Sentences {

    private val SENTENCE_END = Regex("""[.!?…]['")\]]*$""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * A group closes when any of these is true:
     *  * the accumulated text ends with sentence-final punctuation
     *  * the next cue starts more than [maxGapMs] after this one ends
     *  * the group has reached [maxDurationMs] or [maxChars]
     *
     * [maxChars] is 120 rather than the 200 Phase 0 started with: at 200 a Turkish
     * line renders as three or four rows of subtitle, which is more than a viewer can
     * read before the cue changes.
     */
    fun merge(
        cues: List<Cue>,
        maxGapMs: Long = 1200,
        maxDurationMs: Long = 6000,
        maxChars: Int = 120,
    ): List<Sentence> {
        val sentences = mutableListOf<Sentence>()
        val buffer = mutableListOf<Cue>()

        fun flush() {
            if (buffer.isEmpty()) return
            val text = WHITESPACE.replace(buffer.joinToString(" ") { it.text }, " ").trim()
            if (text.isNotEmpty()) {
                sentences.add(
                    Sentence(
                        startMs = buffer.first().startMs,
                        endMs = buffer.last().endMs,
                        text = text,
                        cueCount = buffer.size,
                    ),
                )
            }
            buffer.clear()
        }

        cues.forEachIndexed { index, cue ->
            buffer.add(cue)

            val text = buffer.joinToString(" ") { it.text }.trim()
            val duration = buffer.last().endMs - buffer.first().startMs
            val gapToNext = cues.getOrNull(index + 1)?.let { it.startMs - cue.endMs }

            when {
                SENTENCE_END.containsMatchIn(text) -> flush()
                duration >= maxDurationMs || text.length >= maxChars -> flush()
                gapToNext != null && gapToNext > maxGapMs -> flush()
            }
        }
        flush()
        return sentences
    }

    /**
     * Pairs translated text back onto the source sentences' time ranges.
     *
     * We deliberately do NOT redistribute a translation across the original fragment
     * cues: Turkish word order differs from English, so any such split would put words
     * under the wrong timestamps.
     */
    fun toCues(sentences: List<Sentence>, translations: List<String>): List<Cue> {
        require(sentences.size == translations.size) {
            "sentence/translation count mismatch: ${sentences.size} vs ${translations.size}"
        }
        return sentences.zip(translations)
            .map { (sentence, text) -> Cue(sentence.startMs, sentence.endMs, text.trim()) }
            .filter { it.text.isNotEmpty() }
    }
}
