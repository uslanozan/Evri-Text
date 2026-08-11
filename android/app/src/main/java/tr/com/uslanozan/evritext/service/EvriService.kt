package tr.com.uslanozan.evritext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tr.com.uslanozan.evritext.R
import tr.com.uslanozan.evritext.lounge.AuthState
import tr.com.uslanozan.evritext.lounge.LoungeClient
import tr.com.uslanozan.evritext.lounge.LoungeSession
import tr.com.uslanozan.evritext.ui.MainActivity
import java.io.File
import java.util.Locale

/**
 * Owns the Lounge session for as long as the user is watching.
 *
 * This cannot live in an Activity: the whole point is to follow YouTube, and the
 * moment YouTube comes to the front Android destroys our Activity and cancels its
 * scope. The first on-device test of the Lounge port failed for exactly that reason
 * — the process survived, but the tracking coroutines did not.
 */
class EvriService : LifecycleService() {

    private var session: LoungeSession? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Bağlanıyor…"))
        startSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Restart if the system kills us: losing the session means losing subtitles.
        return START_STICKY
    }

    private fun startSession() {
        val authFile = File(filesDir, AUTH_FILE)
        if (!authFile.exists()) {
            Log.e(TAG, "no pairing at ${authFile.absolutePath}")
            updateNotification("Eşleştirme yok")
            return
        }
        val auth = runCatching { AuthState.decode(authFile.readText()) }.getOrNull()
        if (auth == null || !auth.paired) {
            Log.e(TAG, "pairing file unreadable")
            updateNotification("Eşleştirme okunamadı")
            return
        }

        val client = LoungeClient(deviceName = DEVICE_NAME).apply { loadAuth(auth) }
        val session = LoungeSession(client, lifecycleScope).also { this.session = it }
        current = session
        session.start()

        lifecycleScope.launch {
            session.status.collect { status ->
                updateNotification(
                    when (status) {
                        LoungeSession.Status.DISCONNECTED -> "Bağlı değil"
                        LoungeSession.Status.CONNECTING -> "Bağlanıyor…"
                        LoungeSession.Status.CONNECTED -> "Bağlı"
                    },
                )
            }
        }

        // Step C's whole deliverable: prove the position follows the real playhead.
        lifecycleScope.launch {
            while (currentCoroutineContext().isActive) {
                val tracker = session.tracker
                val prediction = tracker.predict()
                Log.i(
                    PROBE_TAG,
                    "status=${session.status.value} video=${tracker.videoId} " +
                        "state=${tracker.state} advancing=${tracker.advancing} ad=${tracker.inAd} " +
                        "pos=${prediction?.positionS?.let { String.format(Locale.US, "%.2f", it) }} " +
                        "anchorAge=${prediction?.anchorAgeS?.let { String.format(Locale.US, "%.1f", it) }}",
                )
                delay(2000)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Evri-Text",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        session?.stop()
        current = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EvriService"
        private const val PROBE_TAG = "Probe"
        private const val AUTH_FILE = "lounge_auth.json"
        private const val DEVICE_NAME = "Evri-Text"
        private const val CHANNEL_ID = "evritext.session"
        private const val NOTIFICATION_ID = 1

        /**
         * The running session, for the settings screen to read.
         *
         * A plain static reference rather than a bound service: the UI only ever
         * reads, and binding would add a lifecycle to get wrong for no benefit.
         */
        @Volatile
        var current: LoungeSession? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, EvriService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
