package tr.com.uslanozan.evritext.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes only YouTube TV's local playback session; notification contents are never
 * requested, read or stored. Android grants MediaSession access through the system's
 * notification-listener permission, so users still have to opt in explicitly.
 */
class YouTubeSessionListener : NotificationListenerService() {

    private val manager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val component by lazy { ComponentName(this, javaClass) }
    private var controller: MediaController? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener(::attach)
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish(metadata)

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish(controller?.metadata, state)
        }

        override fun onSessionDestroyed() = attach(manager.getActiveSessions(component))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        manager.addOnActiveSessionsChangedListener(sessionListener, component)
        attach(manager.getActiveSessions(component))
    }

    override fun onListenerDisconnected() {
        controller?.unregisterCallback(callback)
        controller = null
        manager.removeOnActiveSessionsChangedListener(sessionListener)
        playback.value = YouTubePlaybackSnapshot.LISTENER_DISCONNECTED
        super.onListenerDisconnected()
    }

    private fun attach(sessions: List<MediaController>?) {
        val next = sessions.orEmpty().firstOrNull { it.packageName == YOUTUBE_PACKAGE }
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback)
        }
        publish(next?.metadata, next?.playbackState)
    }

    private fun publish(
        metadata: MediaMetadata?,
        state: PlaybackState? = controller?.playbackState,
    ) {
        val phase = when (state?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING ->
                YouTubePlaybackSnapshot.PlaybackPhase.PLAYING
            PlaybackState.STATE_PAUSED -> YouTubePlaybackSnapshot.PlaybackPhase.PAUSED
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE ->
                YouTubePlaybackSnapshot.PlaybackPhase.STOPPED
            else -> YouTubePlaybackSnapshot.PlaybackPhase.OTHER
        }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val snapshot = YouTubePlaybackSnapshot(
            listenerConnected = true,
            hasSession = controller != null,
            phase = phase,
            // Ordinary videos populate these fields on this YouTube TV build while
            // Shorts leave all of them blank. Loading also starts blank, hence KEEP
            // rather than DISCONNECT for incomplete metadata.
            hasVideoDetails = title.isNotBlank() || artist.isNotBlank() || art != null,
            positionMs = state?.position?.takeIf { it >= 0L },
            speed = state?.playbackSpeed ?: 0f,
            updatedRealtimeMs = state?.lastPositionUpdateTime?.takeIf { it > 0L },
        )
        playback.value = snapshot
        Log.d(TAG, "phase=$phase session=${snapshot.hasSession} details=${snapshot.hasVideoDetails}")
    }

    companion object {
        private const val TAG = "YouTubeSession"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube.tv"

        private val playback = MutableStateFlow(
            YouTubePlaybackSnapshot.LISTENER_DISCONNECTED,
        )
        val state: StateFlow<YouTubePlaybackSnapshot> = playback

        fun hasAccess(context: Context): Boolean {
            val component = ComponentName(context, YouTubeSessionListener::class.java)
            return context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component)
        }
    }
}
