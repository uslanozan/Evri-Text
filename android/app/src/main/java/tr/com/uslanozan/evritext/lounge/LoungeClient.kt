package tr.com.uslanozan.evritext.lounge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * YouTube Lounge protocol over OkHttp — risk R3.
 *
 * There is no JVM implementation of this protocol, so it is written from scratch
 * against what Phase 0 observed on the wire with `pyytlounge`. The transport is
 * Google's "bind channel": a long-lived chunked HTTP response where each chunk is a
 * decimal length on its own line followed by that many characters of JSON.
 *
 * Two behaviours are not optional, both learned the hard way in Phase 0:
 *  * the screen never sends position on its own, so callers must re-anchor with
 *    [getNowPlaying] on a timer;
 *  * the server closes the bind channel every few minutes, after which every
 *    command fails until the client re-subscribes. See [LoungeSession].
 */
class LoungeClient(
    private val deviceName: String,
    private val http: OkHttpClient = defaultHttpClient(),
) {

    var auth: AuthState = AuthState()
        private set

    private var sid: String? = null
    private var gsession: String? = null
    private var lastEventId: String? = null
    private var commandOffset = 1

    val connected: Boolean get() = sid != null && gsession != null

    fun loadAuth(state: AuthState) {
        auth = state
    }

    // ---------------------------------------------------------------- pairing

    /** Exchange the 12-digit code shown by YouTube for a durable screen id + token. */
    suspend fun pair(pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("pairing_code", pairingCode.filter { it.isDigit() })
            .build()
        val response = post("$API_BASE/pairing/get_screen", body) ?: return@withContext false
        val screen = Json.parseToJsonElement(response).asObjectOrNull()
            ?.get("screen")?.asObjectOrNull() ?: return@withContext false
        auth = auth.copy(
            screenId = screen["screenId"]?.asStringOrNull(),
            loungeIdToken = screen["loungeToken"]?.asStringOrNull(),
        )
        auth.linked
    }

    /**
     * Mint a fresh lounge token for a screen we already know.
     *
     * Note this needs only the screen id — Phase 0's saved pairing has a null
     * refresh token and refreshing works fine, so a null there is not a problem.
     */
    suspend fun refreshAuth(): Boolean = withContext(Dispatchers.IO) {
        val screenId = auth.screenId ?: return@withContext false
        val body = FormBody.Builder().add("screen_ids", screenId).build()
        val response = post("$API_BASE/pairing/get_lounge_token_batch", body)
            ?: return@withContext false
        val screen = Json.parseToJsonElement(response).asObjectOrNull()
            ?.get("screens")?.let { it as? JsonArray }?.firstOrNull()?.asObjectOrNull()
            ?: return@withContext false
        auth = auth.copy(
            screenId = screen["screenId"]?.asStringOrNull() ?: screenId,
            loungeIdToken = screen["loungeToken"]?.asStringOrNull(),
        )
        auth.linked
    }

    // ------------------------------------------------------------- connecting

    /** Open a session. Fills in [sid] and [gsession] from the first chunk. */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (!auth.linked) return@withContext false

        val body = FormBody.Builder()
            .add("app", "web")
            .add("mdx-version", "3")
            .add("name", deviceName)
            .add("id", auth.screenId!!)
            .add("device", "REMOTE_CONTROL")
            .add("capabilities", "que,dsdtr,atp,vsp")
            .add("magnaKey", "cloudPairedDevice")
            .add("ui", "false")
            .add(
                "deviceContext",
                "user_agent=dunno&window_width_points=&window_height_points=&os_name=android&ms=",
            )
            .add("theme", "cl")
            .add("loungeIdToken", auth.loungeIdToken!!)
            .build()

        val url = "$API_BASE/bc/bind".toHttpUrl().newBuilder()
            .addQueryParameter("RID", "1")
            .addQueryParameter("VER", "8")
            .addQueryParameter("CVER", "1")
            .addQueryParameter("auth_failure_option", "send_error")
            .build()

        val request = Request.Builder().url(url).post(body).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) {
                auth = auth.copy(loungeIdToken = null)
                return@withContext false
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "connect -> HTTP ${response.code} ${response.message}")
                return@withContext false
            }
            val text = response.body?.string().orEmpty()
            parseChunks(text.lineSequence().iterator()) { events -> processEvents(events, null) }
            commandOffset = 1
        }
        connected
    }

    /**
     * Read the event stream until the server closes it — which it will, every few
     * minutes. Returning normally means "stream ended", not "finished".
     */
    suspend fun subscribe(onEvent: (LoungeEvent) -> Unit) = withContext(Dispatchers.IO) {
        check(connected) { "not connected" }

        val url = commonParams("$API_BASE/bc/bind".toHttpUrl().newBuilder())
            .addQueryParameter("RID", "rpc")
            .addQueryParameter("CI", "0")
            .addQueryParameter("TYPE", "xmlhttp")
            .build()

        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!handleSessionResult(response.code, response.message)) return@withContext
            if (!response.isSuccessful) {
                Log.w(TAG, "subscribe -> HTTP ${response.code} ${response.message}")
                return@withContext
            }
            val source = response.body?.source() ?: return@withContext
            val lines = generateSequence { source.readUtf8Line() }.iterator()
            parseChunks(lines) { events -> processEvents(events, onEvent) }
        }
    }

    // --------------------------------------------------------------- commands

    /** Ask the screen for its current position. The only re-anchoring tool we have. */
    suspend fun getNowPlaying(): Boolean = command("getNowPlaying")

    /**
     * Turns the screen's autoplay on or off.
     *
     * Needed because merely connecting turns it **on**: the first event the screen
     * sends after `connect()` is `onAutoplayModeChanged {enabled: true}`, regardless
     * of what the viewer set in YouTube's own settings. A lounge session carries its
     * own autoplay state and defaults it on for a remote that advertises queue
     * support, which ours does.
     */
    suspend fun setAutoplayMode(enabled: Boolean): Boolean =
        command("setAutoplayMode", mapOf("autoplayMode" to if (enabled) "ENABLED" else "DISABLED"))

    suspend fun play(): Boolean = command("play")

    suspend fun pause(): Boolean = command("pause")

    suspend fun seekTo(seconds: Double): Boolean =
        command("seekTo", mapOf("newTime" to seconds.toString()))

    suspend fun playVideo(videoId: String): Boolean =
        command("setPlaylist", mapOf("videoId" to videoId))

    private suspend fun command(
        name: String,
        parameters: Map<String, String> = emptyMap(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (!connected) return@withContext false

        // Order matters and is easy to get wrong: the body carries the *current*
        // offset, the query string carries the incremented one.
        val body = FormBody.Builder()
            .add("count", "1")
            .add("ofs", commandOffset.toString())
            .add("req0__sc", name)
            .apply { parameters.forEach { (key, value) -> add("req0_$key", value) } }
            .build()
        commandOffset += 1

        val url = commonParams("$API_BASE/bc/bind".toHttpUrl().newBuilder())
            .addQueryParameter("RID", commandOffset.toString())
            .build()

        val request = Request.Builder().url(url).post(body).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!handleSessionResult(response.code, response.body?.string().orEmpty())) {
                    return@withContext false
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "command $name failed: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- interna

    private fun commonParams(builder: HttpUrl.Builder): HttpUrl.Builder = builder
        .addQueryParameter("name", deviceName)
        .addQueryParameter("loungeIdToken", auth.loungeIdToken.orEmpty())
        .addQueryParameter("SID", sid.orEmpty())
        .apply { lastEventId?.let { addQueryParameter("AID", it) } }
        .addQueryParameter("gsessionid", gsession.orEmpty())
        .addQueryParameter("device", "REMOTE_CONTROL")
        .addQueryParameter("app", "youtube-desktop")
        .addQueryParameter("VER", "8")
        .addQueryParameter("v", "2")

    /** Returns false when the session is gone and the caller must reconnect. */
    private fun handleSessionResult(code: Int, reason: String): Boolean {
        val lost = (code == 400 && reason.contains("Unknown SID")) ||
            (code == 410 && reason.contains("Gone")) ||
            (code == 401 && reason.contains("Expired"))
        if (lost) {
            Log.i(TAG, "session lost: $code $reason")
            connectionLost()
            if (code == 401) auth = auth.copy(loungeIdToken = null)
            return false
        }
        return true
    }

    private fun connectionLost() {
        sid = null
        gsession = null
        lastEventId = null
    }

    private fun post(url: String, body: FormBody): String? {
        val request = Request.Builder().url(url).post(body).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "$url -> HTTP ${response.code}")
                null
            } else {
                response.body?.string()
            }
        }
    }

    /**
     * The bind channel's framing: a decimal length on its own line, then that many
     * characters of JSON spread over the following lines.
     *
     * The length is counted in *characters*, and each consumed line costs its own
     * length plus one for the newline that was stripped — mirroring the reference
     * implementation, which has been running against the live service for years.
     */
    private inline fun parseChunks(lines: Iterator<String>, onEvents: (JsonArray) -> Unit) {
        var remaining = 0
        val chunk = StringBuilder()
        while (lines.hasNext()) {
            val line = lines.next()
            if (remaining <= 0) {
                remaining = line.trim().toIntOrNull() ?: continue
                chunk.setLength(0)
            } else {
                chunk.append(line)
                remaining -= line.length + 1
                if (remaining <= 0) {
                    val parsed = runCatching { Json.parseToJsonElement(chunk.toString()) }.getOrNull()
                    (parsed as? JsonArray)?.let(onEvents)
                    remaining = 0
                }
            }
        }
    }

    /** Each element is `[eventId, [eventType, ...args]]`. */
    private fun processEvents(events: JsonArray, onEvent: ((LoungeEvent) -> Unit)?) {
        for (element in events) {
            val pair = element as? JsonArray ?: continue
            if (pair.size < 2) continue
            val eventId = (pair[0] as? JsonPrimitive)?.content
            val payload = pair[1] as? JsonArray ?: continue
            val type = (payload.getOrNull(0) as? JsonPrimitive)?.content ?: continue
            val args = payload.drop(1)

            when (type) {
                // Session handshake, not user-visible events.
                "c" -> sid = (args.getOrNull(0) as? JsonPrimitive)?.content
                "S" -> gsession = (args.getOrNull(0) as? JsonPrimitive)?.content
                else -> onEvent?.invoke(toEvent(type, args.getOrNull(0) as? JsonObject))
            }
            if (eventId != null) lastEventId = eventId
        }
    }

    private fun toEvent(type: String, data: JsonObject?): LoungeEvent = when (type) {
        "nowPlaying" -> LoungeEvent.NowPlaying(
            videoId = data?.get("videoId")?.asStringOrNull(),
            currentTimeS = data?.get("currentTime")?.asDoubleOrNull(),
            durationS = data?.get("duration")?.asDoubleOrNull(),
            state = data?.get("state")?.asIntOrNull(),
        )

        "onStateChange" -> LoungeEvent.StateChange(
            currentTimeS = data?.get("currentTime")?.asDoubleOrNull(),
            durationS = data?.get("duration")?.asDoubleOrNull(),
            state = data?.get("state")?.asIntOrNull(),
        )

        "onAdStateChange" -> LoungeEvent.AdState(
            adState = data?.get("adState")?.asIntOrNull(),
            contentVideoId = data?.get("contentVideoId")?.asStringOrNull(),
        )

        "adPlaying" -> LoungeEvent.AdPlaying(
            adState = data?.get("adState")?.asIntOrNull(),
            contentVideoId = data?.get("contentVideoId")?.asStringOrNull(),
        )

        "onPlaybackSpeedChanged" -> LoungeEvent.PlaybackSpeed(
            speed = data?.get("playbackSpeed")?.asDoubleOrNull() ?: 1.0,
        )

        "loungeScreenDisconnected" -> {
            connectionLost()
            auth = auth.copy(loungeIdToken = null)
            LoungeEvent.ScreenDisconnected
        }

        else -> LoungeEvent.Unknown(type, data?.toString().orEmpty())
    }

    companion object {
        private const val TAG = "LoungeClient"
        const val API_BASE = "https://www.youtube.com/api/lounge"

        /** `readTimeout = 0` because [subscribe] is meant to sit open for minutes. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private fun kotlinx.serialization.json.JsonElement.asObjectOrNull(): JsonObject? =
    this as? JsonObject

private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun kotlinx.serialization.json.JsonElement.asDoubleOrNull(): Double? =
    (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

private fun kotlinx.serialization.json.JsonElement.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() }
