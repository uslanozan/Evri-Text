package tr.com.uslanozan.evritext.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView

enum class SubtitleColor(val argb: Int) {
    WHITE(Color.WHITE),
    YELLOW(Color.rgb(255, 221, 87)),
    CYAN(Color.rgb(112, 225, 255)),
    GREEN(Color.rgb(128, 255, 160)),
    PINK(Color.rgb(255, 140, 200)),
    ORANGE(Color.rgb(255, 170, 80)),
}

enum class SubtitleSize(val sp: Float) {
    SMALL(18f),
    MEDIUM(22f),
    LARGE(28f),
    EXTRA_LARGE(34f),
}

enum class SubtitleBackground(val alpha: Int) {
    OFF(0),
    LIGHT(115),
    NORMAL(204),
    DARK(242),
}

enum class SubtitlePosition(val bottomMarginDp: Int) {
    BOTTOM(40),
    RAISED(96),
    HIGH(160),
}

data class SubtitleAppearance(
    val color: SubtitleColor = SubtitleColor.WHITE,
    val size: SubtitleSize = SubtitleSize.MEDIUM,
    val background: SubtitleBackground = SubtitleBackground.NORMAL,
    val position: SubtitlePosition = SubtitlePosition.BOTTOM,
)

fun TextView.applySubtitleAppearance(appearance: SubtitleAppearance) {
    setTextColor(appearance.color.argb)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.size.sp)
    background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 6f * resources.displayMetrics.density
        setColor(Color.argb(appearance.background.alpha, 0, 0, 0))
    }
    // The shadow keeps light text readable even when the background is disabled.
    setShadowLayer(6f, 0f, 2f, Color.BLACK)
}
