package tr.com.uslanozan.evritext.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sentence merging is what separates this project's output from YouTube's own
 * Turkish auto-translation, so the boundary rules matter more than they look.
 */
class SentencesTest {

    private fun cue(startS: Double, endS: Double, text: String) =
        Cue((startS * 1000).toLong(), (endS * 1000).toLong(), text)

    @Test
    fun `joins fragments up to a full stop`() {
        val merged = Sentences.merge(
            listOf(
                cue(0.0, 2.0, "so the thing about this"),
                cue(2.0, 4.0, "engine is that it doesn't"),
                cue(4.0, 6.0, "really care."),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals("so the thing about this engine is that it doesn't really care.", merged[0].text)
        assertEquals(0L, merged[0].startMs)
        assertEquals(6_000L, merged[0].endMs)
        assertEquals(3, merged[0].cueCount)
    }

    @Test
    fun `a long silence ends the sentence even without punctuation`() {
        val merged = Sentences.merge(
            listOf(
                cue(0.0, 2.0, "first speaker trails off"),
                cue(10.0, 12.0, "and then much later"),
            ),
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `the character cap ends a sentence when ASR gives no punctuation`() {
        // ASR frequently emits no full stops at all, so this cap does the real work.
        val cues = (0 until 20).map { i ->
            cue(i * 1.0, i * 1.0 + 1.0, "word word word word")
        }

        val merged = Sentences.merge(cues, maxChars = 120)

        assertTrue("nothing was split", merged.size > 1)
        assertTrue(
            "a sentence blew past the cap: ${merged.maxOf { it.text.length }}",
            merged.all { it.text.length < 160 },
        )
    }

    @Test
    fun `pairs translations onto the source time ranges`() {
        val sentences = listOf(
            Sentence(0, 2_000, "hello", 1),
            Sentence(2_000, 4_000, "goodbye", 1),
        )

        val cues = Sentences.toCues(sentences, listOf("merhaba", "hoşça kal"))

        assertEquals(listOf("merhaba", "hoşça kal"), cues.map { it.text })
        assertEquals(0L, cues[0].startMs)
        assertEquals(4_000L, cues[1].endMs)
    }

    @Test
    fun `empty translations are dropped rather than shown blank`() {
        val sentences = listOf(Sentence(0, 1_000, "a", 1), Sentence(1_000, 2_000, "b", 1))
        assertEquals(1, Sentences.toCues(sentences, listOf("çeviri", "  ")).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a count mismatch is a programming error, not something to paper over`() {
        Sentences.toCues(listOf(Sentence(0, 1_000, "a", 1)), listOf("x", "y"))
    }
}
