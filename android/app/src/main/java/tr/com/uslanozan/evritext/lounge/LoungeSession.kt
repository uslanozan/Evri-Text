package tr.com.uslanozan.evritext.lounge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tr.com.uslanozan.evritext.tracking.PositionTracker
import kotlin.math.min

/**
 * Keeps a Lounge session alive and a [PositionTracker] in step with it.
 *
 * Phase 0's first measurement died four minutes in because the reference client
 * treats `subscribe` as a one-shot call: Google closes the bind channel every few
 * minutes, the call simply returns, and every later command throws "not connected".
 * So re-subscribing is part of the protocol, not a nicety — and since the screen
 * never volunteers its position, a re-anchor timer is too.
 */
class LoungeSession(
    private val client: LoungeClient,
    private val scope: CoroutineScope,
    private val reanchorIntervalMs: Long = DEFAULT_REANCHOR_MS,
    /**
     * Restores the viewer's own autoplay choice, which connecting overrides. Left as a
     * parameter because someone who actually wants autoplay should be able to keep it
     * once this reaches the settings screen.
     */
    private val disableAutoplay: Boolean = true,
) {

    val tracker = PositionTracker()

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status

    private val _lastEvent = MutableStateFlow<LoungeEvent?>(null)
    val lastEvent: StateFlow<LoungeEvent?> = _lastEvent

    private var subscribeJob: Job? = null
    private var anchorJob: Job? = null

    fun start() {
        if (subscribeJob != null) return
        subscribeJob = scope.launch { subscribeForever() }
        anchorJob = scope.launch { reanchorForever() }
    }

    fun stop() {
        subscribeJob?.cancel()
        anchorJob?.cancel()
        subscribeJob = null
        anchorJob = null
        _status.value = Status.DISCONNECTED
        // Fire-and-forget on a scope that outlives the cancelled jobs: the screen has
        // to be told, or it keeps treating us as an attached remote and goes on
        // refusing to play Shorts.
        scope.launch { runCatching { client.disconnect() } }
    }

    private suspend fun subscribeForever() {
        var backoffMs = MIN_BACKOFF_MS
        while (scope.isActive) {
            try {
                if (!client.connected) {
                    _status.value = Status.CONNECTING
                    // A 401 clears the token; mint a new one before reconnecting.
                    if (!client.auth.linked && !client.refreshAuth()) {
                        error("refreshAuth failed — pairing is no longer valid")
                    }
                    if (!client.connect()) error("connect failed")
                }
                _status.value = Status.CONNECTED
                backoffMs = MIN_BACKOFF_MS

                if (disableAutoplay) {
                    // Connecting silently switches the screen's autoplay ON, so the
                    // next video starts by itself even though the viewer turned
                    // autoplay off in YouTube. Undo it every time we reconnect — the
                    // screen re-enables it on each new session, not just the first.
                    runCatching { client.setAutoplayMode(enabled = false) }
                        .onFailure { Log.w(TAG, "could not disable autoplay: ${it.message}") }
                }

                // Returns when the server closes the channel: the normal case.
                client.subscribe(::onEvent)
                Log.d(TAG, "event stream ended, resubscribing")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "subscribe loop: ${e.message}")
                _status.value = Status.DISCONNECTED
                delay(backoffMs)
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            }
        }
    }

    /**
     * The screen sends nothing on its own, so we ask. Phase 0 found no rate limit at
     * all — 25 requests at 0.4 s intervals were all answered — and measured drift at
     * 2.8 ms/s, so 20 s leaves an order of magnitude of headroom.
     */
    private suspend fun reanchorForever() {
        while (scope.isActive) {
            delay(reanchorIntervalMs)
            if (client.connected && tracker.advancing) {
                runCatching { client.getNowPlaying() }
                    .onFailure { Log.w(TAG, "re-anchor failed: ${it.message}") }
            }
        }
    }

    private fun onEvent(event: LoungeEvent) {
        when (event) {
            is LoungeEvent.NowPlaying -> {
                tracker.setVideo(event.videoId, event.durationS)
                if (!tracker.inAd) tracker.anchor(event.currentTimeS, event.state)
            }

            is LoungeEvent.StateChange -> {
                // Carries no video id — never treat it as a video change.
                if (!tracker.inAd) tracker.anchor(event.currentTimeS, event.state)
            }

            is LoungeEvent.AdState -> tracker.setAdState(event.adState)
            is LoungeEvent.AdPlaying -> tracker.setAdState(event.adState)
            is LoungeEvent.PlaybackSpeed -> tracker.setSpeed(event.speed)
            is LoungeEvent.ScreenDisconnected -> _status.value = Status.DISCONNECTED
            is LoungeEvent.Unknown -> Log.d(TAG, "unhandled event ${event.type}: ${event.raw}")
        }
        _lastEvent.value = event
    }

    companion object {
        private const val TAG = "LoungeSession"
        const val DEFAULT_REANCHOR_MS = 20_000L
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 15_000L
    }
}
