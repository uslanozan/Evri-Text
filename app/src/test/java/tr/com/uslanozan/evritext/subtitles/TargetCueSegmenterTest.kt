package tr.com.uslanozan.evritext.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCueSegmenterTest {

    @Test
    fun `splits translated sentences on existing source timing anchors`() {
        val sentence = sourceSentence(
            cue(0.0, 2.0, "so the thing about this"),
            cue(2.0, 4.0, "engine is that it doesn't really care"),
            cue(4.0, 6.0, "how much power you feed it"),
        )

        val result = TargetCueSegmenter.segment(
            sentence,
            "Bu motorun olayı şu. Ona ne kadar güç verdiğiniz pek fark etmiyor.",
        )

        assertEquals(2, result.size)
        assertEquals("Bu motorun olayı şu.", result[0].text)
        assertEquals("Ona ne kadar güç verdiğiniz pek fark etmiyor.", result[1].text)
        assertEquals(sentence.startMs, result.first().startMs)
        assertEquals(sentence.endMs, result.last().endMs)
        assertTrue(result[0].endMs in sentence.sourceCues.dropLast(1).map(Cue::endMs))
        assertEquals(result[0].endMs, result[1].startMs)
    }

    @Test
    fun `keeps one short translated sentence unchanged`() {
        val sentence = sourceSentence(cue(0.0, 3.0, "hello there"))

        assertEquals(
            listOf(Cue(0, 3_000, "Merhaba.")),
            TargetCueSegmenter.segment(sentence, "Merhaba."),
        )
    }

    @Test
    fun `uses proportional timing when source has no nearby split anchor`() {
        val sentence = sourceSentence(cue(0.0, 4.0, "one source cue"))
        val translation = "İlk düşünce burada bitiyor. İkinci düşünce burada başlıyor."

        val result = TargetCueSegmenter.segment(sentence, translation)

        assertEquals(2, result.size)
        assertEquals(0, result.first().startMs)
        assertEquals(4_000, result.last().endMs)
        assertEquals(result.first().endMs, result.last().startMs)
    }

    @Test
    fun `falls back when there is not enough minimum display time`() {
        val sentence = sourceSentence(cue(0.0, 1.5, "very fast source"))
        val translation =
            "Bu, okunması oldukça uzun olan ilk cümledir. " +
                "Bu da aynı iki saniyeye sıkışacak oldukça uzun ikinci cümledir."

        assertEquals(
            listOf(Cue(0, 1_500, translation)),
            TargetCueSegmenter.segment(sentence, translation),
        )
    }

    @Test
    fun `speaker turns are treated as semantic pieces`() {
        val sentence = sourceSentence(
            cue(0.0, 2.0, "hello"),
            cue(2.0, 4.0, "how are you"),
        )

        val result = TargetCueSegmenter.segment(sentence, "- Merhaba.\n- Nasılsın?")

        assertEquals(listOf("- Merhaba.", "- Nasılsın?"), result.map(Cue::text))
    }

    @Test
    fun `normalizes YouTube speaker markers into separate subtitle turns`() {
        val sentence = sourceSentence(cue(0.0, 4.1, "two speakers in one ASR cue"))

        val result = TargetCueSegmenter.segment(
            sentence,
            ">> Vay anasını! >> Kesik neredeyse görünmüyor bile.",
        )

        assertEquals(
            listOf("- Vay anasını!", "- Kesik neredeyse görünmüyor bile."),
            result.map(Cue::text),
        )
        assertTrue(result.none { ">>" in it.text })
    }

    @Test
    fun `streams the dense YouTube car-cutting speaker cue`() {
        val sentence = sourceSentence(cue(0.0, 4.07, "dense two-speaker ASR cue"))

        val result = TargetCueSegmenter.segment(
            sentence,
            ">> Vay anasını! >> Kesik neredeyse görünmüyor bile. " +
                "Birinin arabasını ikiye bölsen fark etmezler bile,",
        )

        assertTrue("speaker cue was left as one text wall", result.size > 1)
        assertEquals("- Vay anasını!", result.first().text)
        assertTrue(result.drop(1).none { it.text.startsWith("- Vay") })
        assertTrue(result.none { ">>" in it.text })
    }

    @Test
    fun `streams a dense multi-speaker block instead of restoring one text wall`() {
        val sentence = sourceSentence(cue(0.0, 5.271, "dense source cue"))
        val translation =
            "- Şehirde park sorunu yaşamıyoruz.\n" +
                "- Şehirde daha birkaç dakika olmuştuk ki bu arabayla ilgili internette " +
                "bir sürü video çıktı bile. Lafı almışken"

        val result = TargetCueSegmenter.segment(sentence, translation)

        assertTrue("dense block was not streamed", result.size > 1)
        assertEquals(sentence.startMs, result.first().startMs)
        assertEquals(sentence.endMs, result.last().endMs)
        assertTrue(result.all { it.durationMs >= 850 })
        assertTrue(result.all { it.text.length <= 84 })
        result.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endMs, right.startMs)
        }
    }

    @Test
    fun `merge retains raw timing anchors`() {
        val raw = listOf(
            cue(0.0, 1.0, "this is"),
            cue(1.0, 2.0, "a sentence."),
        )

        val merged = Sentences.merge(raw).single()

        assertEquals(raw, merged.sourceCues)
        assertEquals(2, merged.cueCount)
    }

    private fun sourceSentence(vararg cues: Cue): Sentence = Sentence(
        startMs = cues.first().startMs,
        endMs = cues.last().endMs,
        text = cues.joinToString(" ", transform = Cue::text),
        cueCount = cues.size,
        sourceCues = cues.toList(),
    )

    private fun cue(startS: Double, endS: Double, text: String) =
        Cue((startS * 1000).toLong(), (endS * 1000).toLong(), text)
}
