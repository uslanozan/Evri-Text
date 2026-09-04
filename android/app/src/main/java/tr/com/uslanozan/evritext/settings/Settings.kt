package tr.com.uslanozan.evritext.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * User preferences, shared between the settings screen and the service.
 *
 * They run in the same process, so a SharedPreferences listener is enough to keep
 * both sides in step without binding or broadcasting anything.
 */
class Settings(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val secrets = SecretStore(prefs)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    private val _apiKey = MutableStateFlow(secrets.read() ?: readLegacyApiKey())

    /**
     * Whether subtitles are wanted at all.
     *
     * Defaults to **off**: this sits on top of the TV everyone in the house uses, and
     * a translation appearing unasked over someone's video is worse than one missing.
     * Turning it on is a deliberate act.
     */
    val enabled: StateFlow<Boolean> = _enabled

    /** Never expose this value in the UI; consumers only use it to build a provider. */
    val apiKey: StateFlow<String?> = _apiKey

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when {
                key == KEY_ENABLED -> _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
                secrets.owns(key) -> _apiKey.value = secrets.read() ?: readLegacyApiKey()
            }
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

    fun setApiKey(value: String) {
        val clean = value.trim()
        require(clean.isNotEmpty()) { "API key cannot be blank" }
        prefs.edit().putBoolean(KEY_IGNORE_LEGACY_API_KEY, true).apply()
        secrets.write(clean)
        _apiKey.value = clean
    }

    fun clearApiKey() {
        // An older adb-pushed file may still exist. Once the user has managed the
        // key from the UI, it must not silently become active again.
        prefs.edit().putBoolean(KEY_IGNORE_LEGACY_API_KEY, true).apply()
        secrets.clear()
        _apiKey.value = null
    }

    /** Keeps existing adb-based installs working until the user saves a key in-app. */
    private fun readLegacyApiKey(): String? =
        if (prefs.getBoolean(KEY_IGNORE_LEGACY_API_KEY, false)) null else
            File(appContext.filesDir, LEGACY_API_KEY_FILE)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val FILE = "evritext.settings"
        const val KEY_ENABLED = "subtitles_enabled"
        const val KEY_IGNORE_LEGACY_API_KEY = "ignore_legacy_api_key"
        const val LEGACY_API_KEY_FILE = "gemini_api_key.txt"
    }
}
