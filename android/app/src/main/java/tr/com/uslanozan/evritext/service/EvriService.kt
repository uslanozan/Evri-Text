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
import kotlinx.coroutines.Job
import tr.com.uslanozan.evritext.R
import tr.com.uslanozan.evritext.lounge.AuthState
import tr.com.uslanozan.evritext.lounge.LoungeClient
import tr.com.uslanozan.evritext.lounge.LoungeSession
import tr.com.uslanozan.evritext.overlay.NoticeOverlay
import tr.com.uslanozan.evritext.overlay.SubtitleOverlay
import tr.com.uslanozan.evritext.settings.Settings
import tr.com.uslanozan.evritext.subtitles.Cue
import tr.com.uslanozan.evritext.subtitles.SubtitleEngine
import tr.com.uslanozan.evritext.subtitles.Vtt
import tr.com.uslanozan.evritext.translate.GeminiTranslationProvider
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
    private var overlay: SubtitleOverlay? = null

    private lateinit var settings: Settings
    private val notice by lazy { NoticeOverlay(this) }

    /** Written by the build coroutine, read by the render loop; swapped, never mutated. */
    @Volatile
    private var cues: List<Cue> = emptyList()

    /** The video [cues] belongs to, so toggling off and on does not rebuild it. */
    @Volatile
    private var builtVideoId: String? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        startForeground(NOTIFICATION_ID, buildNotification("Bağlanıyor…"))
        startSession()

        lifecycleScope.launch {
            settings.enabled.collect { on ->
                Log.i(TAG, "subtitles ${if (on) "on" else "off"}")
                if (on) {
                    session?.start()
                } else {
                    // Off has to mean *disconnected*, not merely quiet. YouTube refuses
                    // to play Shorts while any lounge remote is attached — it thinks a
                    // phone is casting — and prompts the viewer to disconnect. Staying
                    // connected with subtitles disabled would break Shorts for someone
                    // who had already switched us off.
                    session?.stop()
                    overlay?.show(null)
                }
            }
        }
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

        // YouTube's "disconnect" dialog is the one lever a viewer has for getting into
        // Shorts without leaving the video app, so honour it: switch ourselves off
        // rather than reconnecting, and say so, or they press it and nothing changes.
        session.onScreenDisconnected = {
            Log.i(TAG, "screen disconnected us — switching subtitles off")
            settings.setEnabled(false)
            notice.show(getString(R.string.notice_disconnected_by_screen), 4_000)
        }
        // Not started here: the enabled flow below owns the connection, so that being
        // switched off leaves the TV with no remote attached to it at all.

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

        startOverlay(session)
        startSubtitles(session)
    }

    /**
     * Builds subtitles for whatever starts playing, and keeps [cues] pointing at the
     * current video's translation. A video change cancels the previous build — nobody
     * is waiting on subtitles for a video they already left.
     */
    private fun startSubtitles(session: LoungeSession) {
        val apiKey = File(filesDir, API_KEY_FILE).takeIf { it.exists() }?.readText()?.trim()
        if (apiKey.isNullOrEmpty()) {
            Log.e(TAG, "no API key at files/$API_KEY_FILE")
            updateNotification("API anahtarı yok")
            return
        }
        val engine = SubtitleEngine(
            provider = GeminiTranslationProvider(apiKey),
            cacheDir = File(cacheDir, "subs"),
        )

        lifecycleScope.launch {
            var job: Job? = null
            while (currentCoroutineContext().isActive) {
                if (!settings.enabled.value) {
                    // Switching off cancels an in-flight build but keeps whatever is
                    // already translated. Discarding it would mean paying for the same
                    // sentences again on the next toggle — and a half-finished build is
                    // never written to the disk cache, so nothing else would save us.
                    job?.cancel()
                    job = null
                    delay(500)
                    continue
                }
                val videoId = session.tracker.videoId
                if (videoId != null && videoId != builtVideoId) {
                    builtVideoId = videoId
                    cues = emptyList()
                    job = launch { buildFor(engine, session, videoId) }
                } else if (videoId != null && job == null && cues.isEmpty()) {
                    // Same video, but the previous build was cancelled before it
                    // produced anything. Pick it back up.
                    job = launch { buildFor(engine, session, videoId) }
                }
                delay(500)
            }
        }
    }

    private suspend fun buildFor(
        engine: SubtitleEngine,
        session: LoungeSession,
        videoId: String,
    ) {
        val startMs = ((session.tracker.predict()?.positionS ?: 0.0) * 1000).toLong()
        Log.i(TAG, "building subtitles for $videoId from ${startMs}ms")
        val started = System.currentTimeMillis()
        var firstCues = true

        // Several seconds of nothing reads as a broken app, so say what is happening —
        // this notice is the only channel we have while YouTube is in front. But a
        // cached video is ready almost instantly, and flashing "preparing" at someone
        // for 200 ms is worse than staying quiet: wait to see if it is actually slow.
        val preparing = lifecycleScope.launch {
            delay(PREPARING_NOTICE_AFTER_MS)
            if (cues.isEmpty()) notice.show(getString(R.string.notice_preparing), 20_000)
        }

        val result = engine.build(videoId, startMs) { partial ->
            cues = partial
            if (firstCues && partial.isNotEmpty()) {
                firstCues = false
                preparing.cancel()
                notice.dismiss()
                Log.i(TAG, "first cues after ${System.currentTimeMillis() - started}ms")
            }
        }
        preparing.cancel()

        when (result) {
            is SubtitleEngine.Result.Ready -> {
                cues = result.cues
                Log.i(
                    TAG,
                    "$videoId ready: ${result.cues.size} cues in " +
                        "${System.currentTimeMillis() - started}ms " +
                        if (result.fromCache) "(cache)" else "(fresh)",
                )
            }

            is SubtitleEngine.Result.AlreadySubtitled -> {
                Log.i(TAG, "$videoId already in ${result.languageTag} — standing down")
                notice.show(getString(R.string.notice_already_target))
            }

            is SubtitleEngine.Result.NoCaptions -> {
                Log.w(TAG, "$videoId has no usable captions (Phase 2 territory)")
                notice.show(getString(R.string.notice_no_captions))
            }

            is SubtitleEngine.Result.Failed -> {
                Log.e(TAG, "$videoId failed: ${result.reason}")
                notice.show(getString(R.string.notice_failed))
            }
        }
    }


    /**
     * Step D: our own overlay, driven at cue granularity.
     *
     * Until the translation pipeline is ported it renders the tracked position, which
     * is the honest thing to show — if this number is right and readable over
     * fullscreen YouTube, subtitles are only a matter of swapping the string.
     */
    private fun startOverlay(session: LoungeSession) {
        val overlay = SubtitleOverlay(this).also { this.overlay = it }
        if (!overlay.attach()) {
            updateNotification("Overlay izni yok")
            return
        }
        lifecycleScope.launch {
            while (currentCoroutineContext().isActive) {
                val tracker = session.tracker
                val prediction = tracker.predict()
                overlay.show(
                    when {
                        !settings.enabled.value -> null
                        // Position is meaningless during ads (R5) and while stopped.
                        tracker.inAd -> null
                        prediction == null -> null
                        !tracker.advancing -> null
                        else -> Vtt.cueAt(cues, (prediction.positionS * 1000).toLong())?.text
                    },
                )
                delay(TICK_MS)
            }
        }
    }

    private fun formatSeconds(total: Double): String {
        val whole = total.toLong().coerceAtLeast(0)
        return String.format(
            Locale.US,
            "%d:%02d:%02d",
            whole / 3600,
            (whole % 3600) / 60,
            whole % 60,
        )
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
        overlay?.detach()
        notice.detach()
        current = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EvriService"
        private const val PROBE_TAG = "Probe"
        private const val AUTH_FILE = "lounge_auth.json"

        /** Pushed with adb until the settings screen can store it properly. */
        private const val API_KEY_FILE = "gemini_api_key.txt"
        private const val DEVICE_NAME = "Evri-Text"
        private const val CHANNEL_ID = "evritext.session"
        private const val NOTIFICATION_ID = 1

        /** Cue changes are perceptible well under a second; 150 ms is comfortably under. */
        private const val TICK_MS = 150L

        /** Long enough that a cache hit never shows the notice at all. */
        private const val PREPARING_NOTICE_AFTER_MS = 1_500L

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
