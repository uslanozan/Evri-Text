package tr.com.uslanozan.evritext.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import tr.com.uslanozan.evritext.R
import tr.com.uslanozan.evritext.settings.Settings
import tr.com.uslanozan.evritext.settings.SubtitleAppearance
import tr.com.uslanozan.evritext.settings.applySubtitleAppearance

/**
 * Our own subtitle window, replacing the TvOverlay scaffold Phase 0 used to prove R4.
 *
 * Phase 0 established that nothing on this device can stop us drawing here: YouTube
 * would need `setHideOverlayWindows()`, which is API 31, and the box is API 30.
 *
 * Unlike the scaffold, updates are a local View change rather than an HTTP call, so
 * the main thread is exactly where this belongs — the "keep drawing off the event
 * loop" rule from Phase 0 was about the network hop, not about drawing.
 */
class SubtitleOverlay(
    private val context: Context,
    private val settings: Settings,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var textView: TextView? = null
    private var shown: String? = null
    private var shownAppearance: SubtitleAppearance? = null

    val canDraw: Boolean
        get() = AndroidSettings.canDrawOverlays(context)

    /**
     * TVs crop the outer edge of the picture, so the window sits above the bottom by
     * a margin rather than flush against it. Not focusable and not touchable: the
     * remote must keep talking to YouTube, never to us.
     */
    private fun layoutParams(appearance: SubtitleAppearance): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (appearance.position.bottomMarginDp * context.resources.displayMetrics.density)
                .toInt()
        }
    }

    fun attach(): Boolean {
        if (root != null) return true
        if (!canDraw) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — see appops in the README")
            return false
        }
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_subtitle, null)
        textView = view.findViewById(R.id.subtitleText)
        val appearance = settings.subtitleAppearance()
        textView?.applySubtitleAppearance(appearance)
        shownAppearance = appearance
        view.visibility = View.GONE
        return try {
            windowManager.addView(view, layoutParams(appearance))
            root = view
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            false
        }
    }

    /** Idempotent: repeated calls with the same text do not touch the view. */
    fun show(text: String?) {
        val appearance = settings.subtitleAppearance()
        if (text == shown && appearance == shownAppearance) return
        shown = text
        val view = root ?: return
        if (appearance != shownAppearance) {
            textView?.applySubtitleAppearance(appearance)
            windowManager.updateViewLayout(view, layoutParams(appearance))
            shownAppearance = appearance
        }
        if (text.isNullOrBlank()) {
            view.visibility = View.GONE
        } else {
            textView?.text = text
            view.visibility = View.VISIBLE
        }
    }

    fun detach() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        textView = null
        shown = null
        shownAppearance = null
    }

    companion object {
        private const val TAG = "SubtitleOverlay"
    }
}
