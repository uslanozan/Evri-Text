package tr.com.uslanozan.evritext.tracking

/**
 * Anchor + interpolate the playhead between Lounge events.
 *
 * Port of `phase0/evri/tracking.py`, and deliberately free of Android and network
 * imports for the same reason the Python original is: this is the heart of R1 and
 * has to stay testable on its own.
 *
 * The Lounge API pushes position only when something changes — Phase 0 confirmed
 * there is no heartbeat at all, not even a slow one — so between events we advance
 * a local clock from the last known anchor. Measured drift: p95 234 ms, ~2.8 ms per
 * second, which is why re-anchoring every 20 s leaves an order of magnitude of room.
 */
class PositionTracker(
    private val clock: Clock = Clock.SYSTEM,
) {

    fun interface Clock {
        /** Monotonic milliseconds. Must not jump when the wall clock is adjusted. */
        fun nowMs(): Long

        companion object {
            val SYSTEM = Clock { System.nanoTime() / 1_000_000 }
        }
    }

    data class Prediction(val positionS: Double, val anchorAgeS: Double)

    var videoId: String? = null
        private set
    var state: Int? = null
        private set
    var speed: Double = 1.0
        private set
    var durationS: Double? = null
        private set

    /**
     * Ads arrive on their own events carrying their own state and currentTime; the
     * content `state` field does not reliably flip to Advertisement. So ad-ness is
     * tracked separately and ad positions are never used as anchors — they are the
     * ad's clock, not the video's.
     */
    var adActive: Boolean = false
        private set

    private var anchorPositionS: Double? = null
    private var anchorMs: Long? = null

    val advancing: Boolean
        get() = state in ADVANCING_STATES && !adActive

    val inAd: Boolean
        get() = adActive || state == STATE_ADVERTISEMENT

    fun setVideo(videoId: String?, durationS: Double?) {
        if (videoId != null && videoId != this.videoId) {
            // New video: the old anchor describes a different timeline.
            anchorPositionS = null
            anchorMs = null
            speed = 1.0
        }
        if (videoId != null) this.videoId = videoId
        if (durationS != null) this.durationS = durationS
    }

    /** Called from ad events only. `null` leaves the flag untouched. */
    fun setAdState(adState: Int?) {
        if (adState != null) adActive = adState == STATE_ADVERTISEMENT
    }

    /** Record a known-good position. Either argument may be null. */
    fun anchor(positionS: Double?, state: Int?) {
        if (state != null) this.state = state
        if (positionS != null) {
            anchorPositionS = positionS
            anchorMs = clock.nowMs()
        }
    }

    /** Change playback speed without losing the time already elapsed at the old one. */
    fun setSpeed(speed: Double) {
        predict()?.let { anchor(it.positionS, null) }
        this.speed = speed
    }

    fun predict(atMs: Long? = null): Prediction? {
        val position = anchorPositionS ?: return null
        val anchoredAt = anchorMs ?: return null
        val now = atMs ?: clock.nowMs()
        val elapsedS = (now - anchoredAt) / 1000.0
        val predicted = if (advancing) position + elapsedS * speed else position
        return Prediction(positionS = predicted, anchorAgeS = elapsedS)
    }

    /**
     * `reported - predicted`. Positive means we were running behind.
     *
     * Must be called BEFORE re-anchoring, otherwise the error is always zero.
     */
    fun errorAgainst(reportedS: Double, atMs: Long? = null): Double? {
        val prediction = predict(atMs) ?: return null
        return reportedS - prediction.positionS
    }

    companion object {
        /** Mirrors pyytlounge's State enum. */
        const val STATE_PLAYING = 1
        const val STATE_PAUSED = 2
        const val STATE_ADVERTISEMENT = 1081

        val ADVANCING_STATES = setOf(STATE_PLAYING)
    }
}
