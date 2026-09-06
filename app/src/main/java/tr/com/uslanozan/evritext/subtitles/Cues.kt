package tr.com.uslanozan.evritext.subtitles

/**
 * Cue model and WebVTT parsing, ported from the validated prototype.
 *
 * YouTube's auto-generated VTT is messy in three specific ways this file exists to
 * undo, and leaving any of them in wrecks the sentence merging downstream:
 *
 *  1. inline word-level timing tags: `hello<00:00:00.539> and<00:00:00.900>`
 *  2. ~10 ms "bridge" cues carrying no new text
 *  3. a rolling window, where each cue repeats the tail of the one before it
 */
data class Cue(val startMs: Long, val endMs: Long, val text: String) {
    val durationMs: Long get() = endMs - startMs
}

object Vtt {

    private val TIMING = Regex(
        """((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""",
    )
    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("""\s+""")
    private val BLOCK_KEYWORDS = listOf("NOTE", "STYLE", "REGION")

    /** `HH:MM:SS.mmm` or `MM:SS.mmm` to milliseconds. */
    fun parseTimestamp(value: String): Long {
        val parts = value.trim().replace(',', '.').split(':')
        val (hours, minutes, seconds) = when (parts.size) {
            3 -> Triple(parts[0], parts[1], parts[2])
            2 -> Triple("0", parts[0], parts[1])
            else -> error("unrecognised timestamp: $value")
        }
        val total = hours.toInt() * 3600 + minutes.toInt() * 60 + seconds.toDouble()
        return Math.round(total * 1000)
    }

    fun formatTimestamp(ms: Long, separator: Char = ','): String {
        val clamped = ms.coerceAtLeast(0)
        val hours = clamped / 3_600_000
        val minutes = (clamped % 3_600_000) / 60_000
        val seconds = (clamped % 60_000) / 1000
        val millis = clamped % 1000
        return "%02d:%02d:%02d%s%03d".format(hours, minutes, seconds, separator, millis)
    }

    /** Parses WebVTT — and SRT, whose timing line is close enough — into raw cues. */
    fun parse(content: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            if (BLOCK_KEYWORDS.any { line.startsWith(it) }) {
                i += 1
                while (i < lines.size && lines[i].isNotBlank()) i += 1
                continue
            }

            val match = TIMING.find(line)
            if (match == null) {
                i += 1
                continue
            }

            val startMs = parseTimestamp(match.groupValues[1])
            val endMs = parseTimestamp(match.groupValues[2])

            i += 1
            val body = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                // A new timing line means the previous cue had no blank separator.
                if (TIMING.containsMatchIn(lines[i])) break
                body.add(lines[i])
                i += 1
            }

            val text = clean(body.joinToString("\n"))
            if (text.isNotEmpty()) cues.add(Cue(startMs, endMs, text.joinToString("\n")))
        }
        return cues
    }

    private fun clean(raw: String): List<String> = TAG.replace(raw, "")
        .let(::unescapeEntities)
        .replace(' ', ' ')
        .split('\n')
        .map { WHITESPACE.replace(it, " ").trim() }
        .filter { it.isNotEmpty() }

    /** ASR captions only ever contain this handful; a full HTML parser is overkill. */
    private fun unescapeEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    /**
     * Undoes YouTube's rolling-window ASR format: drops the bridge cues, then strips
     * each cue's leading lines when the previous cue already emitted them.
     */
    fun dedupeRolling(cues: List<Cue>, minDurationMs: Long = 50): List<Cue> {
        val result = mutableListOf<Cue>()
        var previousLines: List<String> = emptyList()

        for (cue in cues) {
            if (cue.durationMs < minDurationMs) continue

            val lines = cue.text.split('\n').filter { it.isNotEmpty() }
            var index = 0
            while (index < lines.size && lines[index] in previousLines) index += 1
            val fresh = lines.drop(index)

            previousLines = lines
            if (fresh.isEmpty()) continue

            result.add(Cue(cue.startMs, cue.endMs, fresh.joinToString(" ")))
        }
        return result
    }

    fun load(content: String): List<Cue> = dedupeRolling(parse(content))

    /** The cue active at [positionMs], or null. Assumes [cues] is sorted. */
    fun cueAt(cues: List<Cue>, positionMs: Long): Cue? {
        var low = 0
        var high = cues.size - 1
        var found: Cue? = null
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs >= cue.endMs -> low = mid + 1
                else -> {
                    found = cue
                    break
                }
            }
        }
        return found
    }
}
