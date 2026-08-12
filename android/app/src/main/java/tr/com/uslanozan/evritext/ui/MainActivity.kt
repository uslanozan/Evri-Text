package tr.com.uslanozan.evritext.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tr.com.uslanozan.evritext.R
import tr.com.uslanozan.evritext.databinding.ActivityMainBinding
import tr.com.uslanozan.evritext.databinding.ItemSettingRowBinding
import tr.com.uslanozan.evritext.lounge.LoungeSession
import tr.com.uslanozan.evritext.service.EvriService
import tr.com.uslanozan.evritext.settings.Settings
import java.util.Locale

/**
 * Settings and status screen.
 *
 * It owns nothing: the session lives in [EvriService], because this Activity stops
 * existing the moment YouTube comes to the front. All this does is start the service
 * and read from it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        binding.rowEnabled.root.setOnClickListener { settings.toggle() }
        binding.rowApiKey.bind(R.string.setting_api_key, getString(R.string.setting_api_key_empty))
        binding.rowTargetLang.bind(R.string.setting_target_lang, "Türkçe")
        binding.rowModel.bind(R.string.setting_model, "gemini-3.5-flash-lite")
        binding.rowOffset.bind(R.string.setting_offset, "0,0 sn")
        binding.rowCache.bind(R.string.setting_cache, "0 MB")

        // A TV screen with nothing focused swallows the first D-pad press.
        binding.rowEnabled.root.requestFocus()

        EvriService.start(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { renderLoop() }
        }
    }

    private suspend fun renderLoop() {
        while (currentCoroutineContext().isActive) {
            val session = EvriService.current
            val tracker = session?.tracker
            val prediction = tracker?.predict()

            val on = settings.enabled.value
            binding.rowEnabled.bind(
                R.string.setting_enabled,
                if (on) getString(R.string.setting_enabled_on) else getString(R.string.setting_enabled_off),
            )
            binding.rowEnabled.rowValue.setTextColor(
                getColor(if (on) R.color.success else R.color.on_surface_variant),
            )

            binding.statusLine.text = when {
                !on -> getString(R.string.setting_enabled_hint)
                session?.status?.value == LoungeSession.Status.CONNECTED ->
                    getString(R.string.status_connected)
                session?.status?.value == LoungeSession.Status.CONNECTING ->
                    getString(R.string.status_connecting)
                else -> getString(R.string.status_not_connected)
            }

            binding.probeLine.text = buildString {
                append("video     ").append(tracker?.videoId ?: "—").append('\n')
                append("durum     ").append(
                    when {
                        tracker == null -> "—"
                        tracker.inAd -> "reklam"
                        tracker.advancing -> "oynuyor"
                        tracker.state == null -> "—"
                        else -> "duraklatıldı (${tracker.state})"
                    },
                ).append('\n')
                append("pozisyon  ").append(prediction?.let { formatSeconds(it.positionS) } ?: "—")
                tracker?.durationS?.let { append(" / ").append(formatSeconds(it)) }
                append('\n')
                append("çapa yaşı ")
                append(prediction?.let { String.format(Locale.US, "%.1f sn", it.anchorAgeS) } ?: "—")
                if (tracker != null && tracker.speed != 1.0) {
                    append("   hız ").append(tracker.speed).append('x')
                }
            }
            delay(200)
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

    private fun ItemSettingRowBinding.bind(titleRes: Int, value: String) {
        rowTitle.setText(titleRes)
        rowValue.text = value
    }
}
