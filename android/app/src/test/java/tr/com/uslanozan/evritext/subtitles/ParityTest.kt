package tr.com.uslanozan.evritext.subtitles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.com.uslanozan.evritext.captions.CaptionSource

/**
 * Checks the Kotlin port against the numbers Phase 0 produced from the same video.
 *
 * The parsing and merging rules are subtle — rolling-window de-duplication, the
 * character cap standing in for absent punctuation — and a port that is *nearly*
 * right would still produce readable-looking output while quietly shifting every
 * cue boundary. Matching Python exactly is the only cheap way to know.
 */
class ParityTest {

    @Test
    fun `parsing and merging match the Python pipeline`() = runBlocking {
        val source = CaptionSource()
        val track = requireNotNull(source.probe(BIG_STAN).best())
        val vtt = source.download(track)

        val rawCues = Vtt.parse(vtt)
        val cues = Vtt.dedupeRolling(rawCues)
        val sentences = Sentences.merge(cues)

        println("raw cue blocks  ${rawCues.size}")
        println("after dedupe    ${cues.size}   (Python: $PYTHON_CUES)")
        println("sentences       ${sentences.size}   (Python: $PYTHON_SENTENCES)")
        println()
        sentences.take(4).forEach {
            println("[${Vtt.formatTimestamp(it.startMs)} -> ${Vtt.formatTimestamp(it.endMs)}] ${it.text}")
        }

        assertEquals("cue count drifted from the Python pipeline", PYTHON_CUES, cues.size)
        assertEquals("sentence count drifted", PYTHON_SENTENCES, sentences.size)

        val longest = sentences.maxOf { it.text.length }
        assertTrue("a sentence exceeded the character cap: $longest", longest < 200)
    }

    @Test
    fun `cueAt finds the cue covering a position`() {
        val cues = listOf(
            Cue(0, 1000, "bir"),
            Cue(1000, 2500, "iki"),
            Cue(4000, 5000, "üç"),
        )
        assertEquals("bir", Vtt.cueAt(cues, 0)?.text)
        assertEquals("iki", Vtt.cueAt(cues, 2499)?.text)
        assertEquals(null, Vtt.cueAt(cues, 3000)?.text) // gap between cues
        assertEquals("üç", Vtt.cueAt(cues, 4500)?.text)
        assertEquals(null, Vtt.cueAt(cues, 9999)?.text)
    }

    private companion object {
        const val BIG_STAN = "sx8pViXxZQg"

        // From `python 04_make_subs.py --url ...`:
        //   "sx8pViXxZQg: 1305 raw cues -> 826 sentences (track=en, auto=True)"
        const val PYTHON_CUES = 1305
        const val PYTHON_SENTENCES = 826
    }
}
