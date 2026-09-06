package tr.com.uslanozan.evritext.service

import org.junit.Assert.assertEquals
import org.junit.Test
import tr.com.uslanozan.evritext.service.YouTubePlaybackSnapshot.PlaybackPhase

class YouTubePlaybackGateTest {

    @Test
    fun `ordinary video connects once identifying metadata arrives`() {
        assertEquals(
            LoungeGateDecision.CONNECT,
            snapshot(PlaybackPhase.PLAYING, details = true).loungeDecision(),
        )
    }

    @Test
    fun `stopped or absent YouTube session disconnects immediately`() {
        assertEquals(
            LoungeGateDecision.DISCONNECT,
            snapshot(PlaybackPhase.STOPPED, details = true).loungeDecision(),
        )
        assertEquals(
            LoungeGateDecision.DISCONNECT,
            snapshot(PlaybackPhase.OTHER, session = false).loungeDecision(),
        )
    }

    @Test
    fun `loading ads and Shorts do not flap the current decision`() {
        assertEquals(
            LoungeGateDecision.KEEP,
            snapshot(PlaybackPhase.PLAYING, details = false).loungeDecision(),
        )
    }

    @Test
    fun `disconnected listener leaves legacy behavior alone`() {
        assertEquals(
            LoungeGateDecision.KEEP,
            YouTubePlaybackSnapshot.LISTENER_DISCONNECTED.loungeDecision(),
        )
    }

    @Test
    fun `playing position advances from its monotonic anchor`() {
        val playing = snapshot(PlaybackPhase.PLAYING).copy(
            positionMs = 12_000,
            speed = 1.25f,
            updatedRealtimeMs = 5_000,
        )
        assertEquals(14_500L, playing.predictPositionMs(nowRealtimeMs = 7_000))
    }

    @Test
    fun `paused position stays at its anchor`() {
        val paused = snapshot(PlaybackPhase.PAUSED).copy(
            positionMs = 12_000,
            speed = 1f,
            updatedRealtimeMs = 5_000,
        )
        assertEquals(12_000L, paused.predictPositionMs(nowRealtimeMs = 7_000))
    }

    private fun snapshot(
        phase: PlaybackPhase,
        session: Boolean = true,
        details: Boolean = false,
    ) = YouTubePlaybackSnapshot(
        listenerConnected = true,
        hasSession = session,
        phase = phase,
        hasVideoDetails = details,
    )
}
