package tr.com.uslanozan.evritext.subtitles

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/**
 * Turns one fully translated sentence into smaller, readable subtitle events.
 *
 * Translation is deliberately completed before this step, so Turkish word order and
 * context are preserved. Splits are accepted only when they can be placed on timing
 * anchors that already came from YouTube; otherwise the original cue is returned.
 */
object TargetCueSegmenter {

    private const val MAX_EVENT_CHARS = 84 // roughly two 42-character lines
    private const val MIN_EVENT_MS = 850L
    // Netflix's published limit for adult Turkish interlingual subtitles.
    private const val MAX_CHARS_PER_SECOND = 17.0
    // If the source timing is already denser than our readability target, splitting
    // must not make it materially worse — but keeping one giant wall is not safer.
    private const val MAX_DENSITY_INCREASE = 1.05
    private const val SHORT_FRAGMENT_CHARS = 24
    private const val MAX_ANCHOR_SNAP_MS = 700L
    private val CLAUSE_END = Regex("(?<=[,;:])\\s+")
    private val YOUTUBE_SPEAKER_MARKER = Regex("(?:^|\\s+)>>\\s*")
    private val WHITESPACE = Regex("\\s+")

    fun segment(sentence: Sentence, translation: String): List<Cue> {
        val clean = normalizeSpeakerMarkers(translation.trim())
        if (clean.isEmpty()) return emptyList()

        val pieces = targetPieces(clean)
        if (pieces.size <= 1) return listOf(sentence.asCue(clean))
        val totalDuration = sentence.endMs - sentence.startMs
        if (totalDuration < pieces.size * MIN_EVENT_MS) {
            return listOf(sentence.asCue(clean))
        }
        val baselineCharsPerSecond = pieces.sumOf(String::length) * 1000.0 / totalDuration
        val allowedCharsPerSecond = maxOf(
            MAX_CHARS_PER_SECOND,
            baselineCharsPerSecond * MAX_DENSITY_INCREASE,
        )

        val anchors = sentence.sourceCues
            .dropLast(1)
            .map(Cue::endMs)
            .filter { it > sentence.startMs && it < sentence.endMs }
            .distinct()
            .sorted()
        val boundaries = chooseBoundaries(sentence, pieces, anchors, allowedCharsPerSecond)
        val cues = pieces.indices.map { index ->
            Cue(
                startMs = if (index == 0) sentence.startMs else boundaries[index - 1],
                endMs = if (index == pieces.lastIndex) sentence.endMs else boundaries[index],
                text = pieces[index],
            )
        }
        // Anchor candidates obey the density ceiling. When none fits, proportional
        // timing is the least-bad distribution for an already over-dense source; do
        // not collapse it back into the very text wall this class exists to avoid.
        return cues.takeIf(::isReadable)
            ?: listOf(sentence.asCue(clean))
    }

    /** Sentence boundaries first, then punctuation, finally a word-safe hard limit. */
    internal fun targetPieces(text: String): List<String> {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        val sentences = lines.flatMap(::breakSentences)
        return coalesceShortFragments(
            sentences.flatMap(::breakLongPiece).filter(String::isNotEmpty),
        )
    }

    private fun breakSentences(text: String): List<String> {
        val iterator = BreakIterator.getSentenceInstance(Locale.forLanguageTag("tr"))
        iterator.setText(text)
        val pieces = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(pieces::add)
            start = end
            end = iterator.next()
        }
        return pieces.ifEmpty { listOf(text.trim()) }
    }

    private fun breakLongPiece(text: String): List<String> {
        if (text.length <= MAX_EVENT_CHARS) return listOf(text)

        val clauses = text.split(CLAUSE_END).filter(String::isNotBlank)
        if (clauses.size > 1) {
            val grouped = pack(clauses)
            if (grouped.all { it.length <= MAX_EVENT_CHARS }) return grouped
        }
        return pack(text.split(WHITESPACE).filter(String::isNotBlank))
    }

    /** Avoid flashing a tiny unfinished tail while preserving explicit speaker turns. */
    private fun coalesceShortFragments(pieces: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (piece in pieces) {
            val previous = result.lastOrNull()
            val canJoinPrevious = previous != null &&
                piece.length < SHORT_FRAGMENT_CHARS &&
                !piece.startsWith("- ") &&
                !piece.startsWith("[") &&
                previous.length + 1 + piece.length <= MAX_EVENT_CHARS
            if (canJoinPrevious) {
                result[result.lastIndex] = "$previous $piece"
            } else {
                result += piece
            }
        }
        return result
    }

    /** YouTube writes speaker changes as `>>`; render them as subtitle turns. */
    private fun normalizeSpeakerMarkers(text: String): String {
        if (!YOUTUBE_SPEAKER_MARKER.containsMatchIn(text)) return text
        return YOUTUBE_SPEAKER_MARKER.replace(text, "\n- ")
            .lines()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n") { if (it.startsWith("- ")) it else "- $it" }
    }

    private fun pack(parts: List<String>): List<String> {
        val result = mutableListOf<String>()
        var current = ""
        for (part in parts) {
            val candidate = if (current.isEmpty()) part else "$current $part"
            if (candidate.length <= MAX_EVENT_CHARS || current.isEmpty()) {
                current = candidate
            } else {
                result += current
                current = part
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun chooseBoundaries(
        sentence: Sentence,
        pieces: List<String>,
        anchors: List<Long>,
        allowedCharsPerSecond: Double,
    ): List<Long> {
        val totalChars = pieces.sumOf { it.length }.coerceAtLeast(1)
        val totalDuration = sentence.endMs - sentence.startMs
        val selected = mutableListOf<Long>()
        var previous = sentence.startMs
        var charsBefore = 0

        for (pieceIndex in 0 until pieces.lastIndex) {
            charsBefore += pieces[pieceIndex].length
            val desired = sentence.startMs + totalDuration * charsBefore / totalChars
            val remainingPieces = pieces.size - pieceIndex - 1
            val earliest = previous + MIN_EVENT_MS
            val latest = sentence.endMs - remainingPieces * MIN_EVENT_MS
            val proportional = desired.coerceIn(earliest, latest)
            val candidates = anchors.filter { anchor ->
                anchor > previous &&
                    anchor in earliest..latest &&
                    anchor !in selected &&
                    isWithinReadingSpeed(
                        pieces[pieceIndex],
                        anchor - previous,
                        allowedCharsPerSecond,
                    ) &&
                    isWithinReadingSpeed(
                        pieces.drop(pieceIndex + 1).joinToString(""),
                        sentence.endMs - anchor,
                        allowedCharsPerSecond,
                    )
            }
            val nearestAnchor = candidates.minByOrNull { abs(it - desired) }
            val chosen = nearestAnchor
                ?.takeIf { abs(it - desired) <= MAX_ANCHOR_SNAP_MS }
                ?: proportional
            selected += chosen
            previous = chosen
        }
        return selected
    }

    private fun isReadable(cues: List<Cue>): Boolean = cues.all { cue ->
        cue.durationMs >= MIN_EVENT_MS &&
            cue.text.length <= MAX_EVENT_CHARS
    }

    private fun isWithinReadingSpeed(
        text: String,
        durationMs: Long,
        maxCharsPerSecond: Double = MAX_CHARS_PER_SECOND,
    ): Boolean = durationMs > 0 && text.length * 1000.0 / durationMs <= maxCharsPerSecond

    private fun Sentence.asCue(text: String) = Cue(startMs, endMs, text)
}
