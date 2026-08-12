package tr.com.uslanozan.evritext.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User preferences, shared between the settings screen and the service.
 *
 * They run in the same process, so a SharedPreferences listener is enough to keep
 * both sides in step without binding or broadcasting anything.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    /**
     * Whether subtitles are wanted at all.
     *
     * Defaults to **off**: this sits on top of the TV everyone in the house uses, and
     * a translation appearing unasked over someone's video is worse than one missing.
     * Turning it on is a deliberate act.
     */
    val enabled: StateFlow<Boolean> = _enabled

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun toggle(): Boolean {
        val next = !_enabled.value
        setEnabled(next)
        return next
    }

    private companion object {
        const val FILE = "evritext.settings"
        const val KEY_ENABLED = "subtitles_enabled"
    }
}
