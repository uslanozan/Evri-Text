package tr.com.uslanozan.evritext.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import tr.com.uslanozan.evritext.R

/**
 * Short-lived confirmation in the corner, for when something changed while the user
 * is looking at YouTube rather than at us.
 *
 * A separate window from [SubtitleOverlay] on purpose: a notice must be able to appear
 * while a subtitle is on screen, and neither should have to know about the other.
 */
class NoticeOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var textView: TextView? = null
    private val hide = Runnable { detach() }

    fun show(text: String, durationMs: Long = DEFAULT_DURATION_MS) {
        handler.post {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "no overlay permission, dropping notice: $text")
                return@post
            }
            if (root == null && !attach()) return@post
            textView?.text = text
            handler.removeCallbacks(hide)
            handler.postDelayed(hide, durationMs)
        }
    }

    private fun attach(): Boolean {
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_notice, null)
        textView = view.findViewById(R.id.noticeText)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = context.resources.getDimensionPixelSize(R.dimen.notice_margin)
            y = context.resources.getDimensionPixelSize(R.dimen.notice_margin)
        }
        return try {
            windowManager.addView(view, params)
            root = view
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            false
        }
    }

    /** Takes it down early, for when the thing it announced has finished. */
    fun dismiss() {
        handler.post(hide)
    }

    fun detach() {
        handler.removeCallbacks(hide)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        textView = null
    }

    private companion object {
        const val TAG = "NoticeOverlay"

        /** Long enough to read a three-word confirmation, short enough not to nag. */
        const val DEFAULT_DURATION_MS = 2_500L
    }
}
