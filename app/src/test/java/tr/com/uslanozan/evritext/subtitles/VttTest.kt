package tr.com.uslanozan.evritext.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Offline tests for the three things YouTube's ASR VTT does that break everything
 * downstream. The [ParityTest] proves the whole file matches Python; these pin the
 * individual rules so a failure says *which* one broke.
 */
class VttTest {

    @Test
    fun `parses timestamps in both shapes`() {
        assertEquals(0L, Vtt.parseTimestamp("00:00:00.000"))
        assertEquals(17_920L, Vtt.parseTimestamp("00:00:17.920"))
        assertEquals(3_723_450L, Vtt.parseTimestamp("01:02:03.450"))
        assertEquals(63_450L, Vtt.parseTimestamp("01:03.450"))
        // SRT uses a comma; the same parser has to take it.
        assertEquals(17_920L, Vtt.parseTimestamp("00:00:17,920"))
    }

    @Test
    fun `strips inline word timing tags`() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            hello<00:00:01.539><c> and</c><00:00:01.900><c> welcome</c>
        """.trimIndent()

        assertEquals("hello and welcome", Vtt.parse(vtt).single().text)
    }

    @Test
    fun `drops bridge cues and repeated leading lines`() {
        // The real rolling-window shape: a 10 ms bridge, then a cue repeating the
        // previous line before adding a new one.
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            first line

            00:00:03.000 --> 00:00:03.010
            first line

            00:00:03.010 --> 00:00:05.000
            first line
            second line
        """.trimIndent()

        val cues = Vtt.load(vtt)

        assertEquals(2, cues.size)
        assertEquals("first line", cues[0].text)
        assertEquals("second line", cues[1].text)
    }

    @Test
    fun `skips NOTE blocks and decodes entities`() {
        val vtt = """
            WEBVTT

            NOTE this is a comment
            that spans two lines

            00:00:01.000 --> 00:00:02.000
            rock &amp; roll &quot;quoted&quot;
        """.trimIndent()

        assertEquals("rock & roll \"quoted\"", Vtt.parse(vtt).single().text)
    }

    @Test
    fun `formats timestamps back out`() {
        assertEquals("00:00:17,920", Vtt.formatTimestamp(17_920))
        assertEquals("01:02:03.450", Vtt.formatTimestamp(3_723_450, '.'))
        assertEquals("00:00:00,000", Vtt.formatTimestamp(-5))
    }
}
