package tr.com.uslanozan.evritext.lounge

/**
 * The events Phase 0 actually observed on the wire, and nothing more.
 *
 * The screen sends a lot of chatter; only these carry information the subtitle
 * pipeline needs. Everything else arrives as [Unknown] so it can be logged while we
 * learn the protocol, exactly as the Python probe did.
 */
sealed interface LoungeEvent {

    /** Full state including the video id. The only event that tells us *what* is playing. */
    data class NowPlaying(
        val videoId: String?,
        val currentTimeS: Double?,
        val durationS: Double?,
        val state: Int?,
    ) : LoungeEvent

    /** Position and state, but **no video id** — never treat it as a video change. */
    data class StateChange(
        val currentTimeS: Double?,
        val durationS: Double?,
        val state: Int?,
    ) : LoungeEvent

    data class AdState(val adState: Int?, val contentVideoId: String?) : LoungeEvent

    data class AdPlaying(val adState: Int?, val contentVideoId: String?) : LoungeEvent

    data class PlaybackSpeed(val speed: Double) : LoungeEvent

    /** The screen dropped us; the lounge token has to be refreshed before reconnecting. */
    data object ScreenDisconnected : LoungeEvent

    data class Unknown(val type: String, val raw: String) : LoungeEvent
}
