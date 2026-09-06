package tr.com.uslanozan.evritext.service

/** What the local YouTube TV MediaSession currently exposes. */
data class YouTubePlaybackSnapshot(
    val listenerConnected: Boolean,
    val hasSession: Boolean,
    val phase: PlaybackPhase,
    val hasVideoDetails: Boolean,
    val positionMs: Long? = null,
    val speed: Float = 0f,
    val updatedRealtimeMs: Long? = null,
) {
    enum class PlaybackPhase { PLAYING, PAUSED, STOPPED, OTHER }

    companion object {
        val LISTENER_DISCONNECTED = YouTubePlaybackSnapshot(
            listenerConnected = false,
            hasSession = false,
            phase = PlaybackPhase.OTHER,
            hasVideoDetails = false,
        )
    }
}

/** Interpolate Android's last MediaSession anchor to the requested monotonic time. */
fun YouTubePlaybackSnapshot.predictPositionMs(nowRealtimeMs: Long): Long? {
    val anchor = positionMs ?: return null
    val updated = updatedRealtimeMs ?: return anchor
    if (phase != YouTubePlaybackSnapshot.PlaybackPhase.PLAYING) return anchor
    return (anchor + (nowRealtimeMs - updated).coerceAtLeast(0L) * speed.toDouble())
        .toLong()
        .coerceAtLeast(0L)
}

/**
 * A decision rather than a Boolean: incomplete metadata must not flap an existing
 * Lounge connection during an ad or the first few hundred milliseconds of loading.
 */
enum class LoungeGateDecision { CONNECT, DISCONNECT, KEEP }

fun YouTubePlaybackSnapshot.loungeDecision(): LoungeGateDecision = when {
    !listenerConnected -> LoungeGateDecision.KEEP
    !hasSession || phase == YouTubePlaybackSnapshot.PlaybackPhase.STOPPED ->
        LoungeGateDecision.DISCONNECT
    hasVideoDetails -> LoungeGateDecision.CONNECT
    else -> LoungeGateDecision.KEEP
}
