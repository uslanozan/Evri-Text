package tr.com.uslanozan.evritext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import tr.com.uslanozan.evritext.R
import tr.com.uslanozan.evritext.lounge.LoungeClient
import tr.com.uslanozan.evritext.lounge.LoungeSession
import tr.com.uslanozan.evritext.lounge.PairingStore
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
    private var settingsVisible = false
    private var loungeRunning = false
    private val sessionJobs = mutableListOf<Job>()

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
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_connecting)))
        startSession()

        lifecycleScope.launch {
            settings.enabled.collect { on ->
                Log.i(TAG, "subtitles ${if (on) "on" else "off"}")
                reconcileSession()
                if (!on) {
                    // Off has to mean *disconnected*, not merely quiet. YouTube refuses
                    // to play Shorts while any lounge remote is attached — it thinks a
                    // phone is casting — and prompts the viewer to disconnect. Staying
                    // connected with subtitles disabled would break Shorts for someone
                    // who had already switched us off.
                    overlay?.show(null)
                }
            }
        }

        lifecycleScope.launch {
            YouTubeSessionListener.state.collect { reconcileSession() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_RELOAD_PAIRING -> restartSession()
            ACTION_SET_SETTINGS_VISIBLE -> {
                settingsVisible = intent.getBooleanExtra(EXTRA_VISIBLE, false)
                if (settingsVisible) overlay?.show(null)
            }
        }
        // Restart if the system kills us: losing the session means losing subtitles.
        return START_STICKY
    }

    private fun startSession() {
        val auth = PairingStore(this).load()
        if (auth == null) {
            Log.e(TAG, "no valid pairing")
            updateNotification(getString(R.string.status_no_pairing))
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

        sessionJobs += lifecycleScope.launch {
            session.status.collect { status ->
                updateNotification(
                    when (status) {
                        LoungeSession.Status.DISCONNECTED -> getString(R.string.status_not_connected)
                        LoungeSession.Status.CONNECTING -> getString(R.string.status_connecting)
                        LoungeSession.Status.CONNECTED -> getString(R.string.status_connected)
                    },
                )
            }
        }

        // Step C's whole deliverable: prove the position follows the real playhead.
        sessionJobs += lifecycleScope.launch {
            while (currentCoroutineContext().isActive) {
                val tracker = session.tracker
                val prediction = tracker.predict()
                val mediaPositionS = YouTubeSessionListener.state.value
                    .predictPositionMs(SystemClock.elapsedRealtime())
                    ?.div(1000.0)
                val deltaMs = if (prediction != null && mediaPositionS != null) {
                    ((mediaPositionS - prediction.positionS) * 1000).toLong()
                } else {
                    null
                }
                Log.i(
                    PROBE_TAG,
                    "status=${session.status.value} video=${tracker.videoId} " +
                        "state=${tracker.state} advancing=${tracker.advancing} ad=${tracker.inAd} " +
                        "pos=${prediction?.positionS?.let { String.format(Locale.US, "%.2f", it) }} " +
                        "mediaPos=${mediaPositionS?.let { String.format(Locale.US, "%.2f", it) }} " +
                        "mediaDeltaMs=$deltaMs " +
                        "anchorAge=${prediction?.anchorAgeS?.let { String.format(Locale.US, "%.1f", it) }}",
                )
                delay(2000)
            }
        }

        startOverlay(session)
        startSubtitles(session)
        reconcileSession()
    }

    /**
     * Keep Lounge attached only while an ordinary YouTube video is actually open.
     * Without the optional media permission, retain the old always-connected behavior
     * so an upgrade never makes an existing installation stop working.
     */
    private fun reconcileSession() {
        val activeSession = session ?: return
        val wanted = when {
            !settings.enabled.value -> false
            !YouTubeSessionListener.hasAccess(this) -> true
            else -> when (YouTubeSessionListener.state.value.loungeDecision()) {
                LoungeGateDecision.CONNECT -> true
                LoungeGateDecision.DISCONNECT -> false
                LoungeGateDecision.KEEP -> loungeRunning
            }
        }
        if (wanted == loungeRunning) return
        loungeRunning = wanted
        if (wanted) {
            Log.i(TAG, "ordinary YouTube video detected — connecting Lounge")
            activeSession.start()
        } else {
            Log.i(TAG, "YouTube playback ended or left long-form — disconnecting Lounge")
            activeSession.stop()
            overlay?.show(null)
        }
    }

    /** Replace the whole session after pairing changes without stacking render loops. */
    private fun restartSession() {
        sessionJobs.forEach(Job::cancel)
        sessionJobs.clear()
        session?.stop()
        loungeRunning = false
        session = null
        current = null
        overlay?.detach()
        overlay = null
        notice.dismiss()
        cues = emptyList()
        builtVideoId = null
        startSession()
    }

    /**
     * Builds subtitles for whatever starts playing, and keeps [cues] pointing at the
     * current video's translation. A video change cancels the previous build — nobody
     * is waiting on subtitles for a video they already left.
     */
    private fun startSubtitles(session: LoungeSession) {
        sessionJobs += lifecycleScope.launch {
            var job: Job? = null
            var activeApiKey: String? = null
            var engine: SubtitleEngine? = null
            while (currentCoroutineContext().isActive) {
                val apiKey = settings.apiKey.value
                if (apiKey != activeApiKey) {
                    job?.cancel()
                    job = null
                    cues = emptyList()
                    builtVideoId = null
                    activeApiKey = apiKey
                    engine = apiKey?.let {
                        SubtitleEngine(
                            provider = GeminiTranslationProvider(it),
                            cacheDir = File(cacheDir, "subs"),
                        )
                    }
                    updateNotification(
                        getString(
                            if (apiKey == null) R.string.status_api_key_required
                            else R.string.status_ready,
                        ),
                    )
                }
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
                val activeEngine = engine
                if (activeEngine == null) {
                    delay(500)
                    continue
                }
                val videoId = session.tracker.videoId
                if (videoId != null && videoId != builtVideoId) {
                    // The previous request may be inside a blocking network call and
                    // return after cancellation. Cancel it now; buildFor also guards
                    // every publication so a late response can never restore cues
                    // belonging to the video the viewer already left.
                    job?.cancel()
                    builtVideoId = videoId
                    cues = emptyList()
                    notice.dismiss()
                    job = launch { buildFor(activeEngine, session, videoId) }
                } else if (videoId != null && job == null && cues.isEmpty()) {
                    // Same video, but the previous build was cancelled before it
                    // produced anything. Pick it back up.
                    job = launch { buildFor(activeEngine, session, videoId) }
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
        val buildJob = currentCoroutineContext()[Job]

        fun isCurrentBuild(): Boolean =
            buildJob?.isActive == true && builtVideoId == videoId &&
                session.tracker.videoId == videoId && settings.enabled.value

        // Several seconds of nothing reads as a broken app, so say what is happening —
        // this notice is the only channel we have while YouTube is in front. But a
        // cached video is ready almost instantly, and flashing "preparing" at someone
        // for 200 ms is worse than staying quiet: wait to see if it is actually slow.
        val preparing = CoroutineScope(currentCoroutineContext()).launch {
            delay(PREPARING_NOTICE_AFTER_MS)
            if (isCurrentBuild() && cues.isEmpty()) {
                notice.show(getString(R.string.notice_preparing), 20_000)
            }
        }

        val result = engine.build(videoId, startMs) { partial ->
            if (!isCurrentBuild()) return@build
            cues = partial
            if (firstCues && partial.isNotEmpty()) {
                firstCues = false
                preparing.cancel()
                notice.dismiss()
                Log.i(TAG, "first cues after ${System.currentTimeMillis() - started}ms")
            }
        }
        preparing.cancel()
        currentCoroutineContext().ensureActive()
        if (!isCurrentBuild()) return

        when (result) {
            is SubtitleEngine.Result.Ready -> {
                cues = result.cues
                Log.i(
                    TAG,
                    "$videoId ready: ${result.cues.size} cues in " +
                        "${System.currentTimeMillis() - started}ms " +
                        if (result.fromCache) "(cache)" else "(fresh)",
                )
                if (result.warning != null) {
                    notice.show(getString(R.string.notice_translation_partial), 5_000)
                }
            }

            is SubtitleEngine.Result.AlreadySpoken -> {
                Log.i(TAG, "$videoId audio already in ${result.languageTag} — standing down")
                notice.show(getString(R.string.notice_already_spoken))
            }

            is SubtitleEngine.Result.AlreadySubtitled -> {
                Log.i(TAG, "$videoId already has ${result.languageTag} subtitles — standing down")
                notice.show(getString(R.string.notice_already_subtitled))
            }

            is SubtitleEngine.Result.NoCaptions -> {
                Log.w(TAG, "$videoId has no usable captions (Phase 2 territory)")
                notice.show(getString(R.string.notice_no_captions))
            }

            is SubtitleEngine.Result.Failed -> {
                Log.e(TAG, "$videoId failed: ${result.reason}")
                cues = emptyList()
                notice.show(failureMessage(result.reason), 5_000)
            }
        }
    }

    private fun failureMessage(reason: String): String {
        val message = reason.lowercase(Locale.ROOT)
        val resource = when {
            "429" in message || "resource_exhausted" in message -> R.string.notice_quota_failed
            "translation failed" in message && (
                "401" in message || "403" in message || "api_key" in message ||
                    "permission_denied" in message
                ) -> R.string.notice_api_key_failed
            "timeout" in message || "unable to resolve" in message ||
                "failed to connect" in message || "network" in message ->
                R.string.notice_network_failed
            "caption" in message -> R.string.notice_caption_failed
            else -> R.string.notice_failed
        }
        return getString(resource)
    }


    /**
     * Step D: our own overlay, driven at cue granularity.
     *
     * Until the translation pipeline is ported it renders the tracked position, which
     * is the honest thing to show — if this number is right and readable over
     * fullscreen YouTube, subtitles are only a matter of swapping the string.
     */
    private fun startOverlay(session: LoungeSession) {
        val overlay = SubtitleOverlay(this, settings).also { this.overlay = it }
        if (!overlay.attach()) {
            updateNotification("Overlay izni yok")
            return
        }
        sessionJobs += lifecycleScope.launch {
            while (currentCoroutineContext().isActive) {
                val tracker = session.tracker
                val prediction = tracker.predict()
                overlay.show(
                    when {
                        !settings.enabled.value -> null
                        settingsVisible -> null
                        !loungeRunning -> null
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

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Evri Text",
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
        sessionJobs.forEach(Job::cancel)
        sessionJobs.clear()
        session?.stop()
        loungeRunning = false
        overlay?.detach()
        notice.detach()
        current = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EvriService"
        private const val PROBE_TAG = "Probe"
        private const val DEVICE_NAME = "Evri Text"
        private const val ACTION_RELOAD_PAIRING =
            "tr.com.uslanozan.evritext.action.RELOAD_PAIRING"
        private const val ACTION_SET_SETTINGS_VISIBLE =
            "tr.com.uslanozan.evritext.action.SET_SETTINGS_VISIBLE"
        private const val EXTRA_VISIBLE = "visible"
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

        fun reloadPairing(context: Context) {
            val intent = Intent(context, EvriService::class.java).setAction(ACTION_RELOAD_PAIRING)
            start(context, intent)
        }

        fun setSettingsVisible(context: Context, visible: Boolean) {
            val intent = Intent(context, EvriService::class.java)
                .setAction(ACTION_SET_SETTINGS_VISIBLE)
                .putExtra(EXTRA_VISIBLE, visible)
            start(context, intent)
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
