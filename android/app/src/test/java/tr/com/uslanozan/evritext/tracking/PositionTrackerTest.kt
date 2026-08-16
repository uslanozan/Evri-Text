package tr.com.uslanozan.evritext.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.com.uslanozan.evritext.tracking.PositionTracker.Companion.STATE_ADVERTISEMENT
import tr.com.uslanozan.evritext.tracking.PositionTracker.Companion.STATE_PAUSED
import tr.com.uslanozan.evritext.tracking.PositionTracker.Companion.STATE_PLAYING

/**
 * The playhead maths, driven by a clock we control.
 *
 * This is where a subtitle ends up on the wrong line, and it is invisible from the
 * outside: a tracker that is quietly 400 ms off still looks like it works. Phase 0
 * measured the real drift at p95 234 ms; these tests pin the arithmetic that number
 * depends on.
 */
class PositionTrackerTest {

    /** Advanced by hand so a test never waits on wall-clock time. */
    private class FakeClock(var ms: Long = 0) : PositionTracker.Clock {
        override fun nowMs(): Long = ms
        fun advance(by: Long) { ms += by }
    }

    private val clock = FakeClock()
    private val tracker = PositionTracker(clock)

    @Test
    fun `predicts nothing before the first anchor`() {
        assertNull(tracker.predict())
        assertNull(tracker.errorAgainst(reportedS = 10.0))
    }

    @Test
    fun `advances in real time while playing`() {
        tracker.anchor(positionS = 100.0, state = STATE_PLAYING)
        clock.advance(5_000)

        val prediction = tracker.predict()!!
        assertEquals(105.0, prediction.positionS, 0.001)
        assertEquals(5.0, prediction.anchorAgeS, 0.001)
    }

    @Test
    fun `freezes while paused`() {
        tracker.anchor(positionS = 100.0, state = STATE_PLAYING)
        clock.advance(2_000)
        tracker.anchor(positionS = null, state = STATE_PAUSED)
        clock.advance(30_000)

        // Pausing freezes the playhead where it actually was — 102, not the 100 the
        // last anchor happened to carry — and it stays there however long we wait.
        assertEquals(102.0, tracker.predict()!!.positionS, 0.001)
        clock.advance(60_000)
        assertEquals(102.0, tracker.predict()!!.positionS, 0.001)
        assertFalse(tracker.advancing)
    }

    @Test
    fun `speed change keeps the time already elapsed at the old speed`() {
        tracker.anchor(positionS = 10.0, state = STATE_PLAYING)
        clock.advance(10_000) // 10 s of content at 1.0x

        tracker.setSpeed(2.0)
        clock.advance(10_000) // 10 s of wall clock at 2.0x = 20 s of content

        assertEquals(40.0, tracker.predict()!!.positionS, 0.001)
    }

    @Test
    fun `ads do not advance the video clock`() {
        tracker.anchor(positionS = 50.0, state = STATE_PLAYING)
        tracker.setAdState(STATE_ADVERTISEMENT)
        clock.advance(30_000)

        assertTrue(tracker.inAd)
        assertFalse(tracker.advancing)
        // The 30 s belonged to the ad, not to the video — the video is still at 50.
        assertEquals(50.0, tracker.predict()!!.positionS, 0.001)

        tracker.setAdState(0)
        clock.advance(1_000)
        assertFalse(tracker.inAd)
        assertEquals(51.0, tracker.predict()!!.positionS, 0.001)
    }

    @Test
    fun `null ad state leaves the flag alone`() {
        tracker.setAdState(STATE_ADVERTISEMENT)
        tracker.setAdState(null)
        assertTrue("an event without ad info must not clear the flag", tracker.adActive)
    }

    @Test
    fun `error is measured against the prediction before re-anchoring`() {
        tracker.anchor(positionS = 100.0, state = STATE_PLAYING)
        clock.advance(20_000) // we predict 120.0

        // The screen says 120.3: we were running 300 ms behind.
        assertEquals(0.3, tracker.errorAgainst(reportedS = 120.3)!!, 0.001)

        tracker.anchor(positionS = 120.3, state = STATE_PLAYING)
        assertEquals(0.0, tracker.errorAgainst(reportedS = 120.3)!!, 0.001)
    }

    @Test
    fun `a new video discards the previous anchor`() {
        tracker.setVideo("first", durationS = 600.0)
        tracker.anchor(positionS = 300.0, state = STATE_PLAYING)
        clock.advance(1_000)

        tracker.setVideo("second", durationS = 120.0)

        // Without this, the first frame of the new video would be captioned with a
        // line from five minutes into the old one.
        assertNull(tracker.predict())
        assertEquals("second", tracker.videoId)
        assertEquals(120.0, tracker.durationS!!, 0.001)
        assertEquals("speed must reset with the video", 1.0, tracker.speed, 0.001)
    }

    @Test
    fun `the same video id does not reset the anchor`() {
        tracker.setVideo("same", durationS = 600.0)
        tracker.anchor(positionS = 42.0, state = STATE_PLAYING)
        tracker.setVideo("same", durationS = 600.0)

        assertEquals(42.0, tracker.predict()!!.positionS, 0.001)
    }

    @Test
    fun `state-only events keep the position`() {
        tracker.anchor(positionS = 10.0, state = STATE_PLAYING)
        clock.advance(1_000)
        // onStateChange carries no video id and sometimes no position.
        tracker.anchor(positionS = null, state = STATE_PLAYING)
        clock.advance(1_000)

        assertEquals(12.0, tracker.predict()!!.positionS, 0.001)
    }
}
