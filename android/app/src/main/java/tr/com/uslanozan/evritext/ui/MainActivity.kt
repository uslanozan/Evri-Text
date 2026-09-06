package tr.com.uslanozan.evritext.ui

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import tr.com.uslanozan.evritext.R
import tr.com.uslanozan.evritext.databinding.ActivityMainBinding
import tr.com.uslanozan.evritext.databinding.ItemSettingRowBinding
import tr.com.uslanozan.evritext.lounge.LoungeClient
import tr.com.uslanozan.evritext.lounge.LoungeSession
import tr.com.uslanozan.evritext.lounge.PairingStore
import tr.com.uslanozan.evritext.service.EvriService
import tr.com.uslanozan.evritext.settings.Settings
import tr.com.uslanozan.evritext.settings.SubtitleAppearance
import tr.com.uslanozan.evritext.settings.SubtitleBackground
import tr.com.uslanozan.evritext.settings.SubtitleColor
import tr.com.uslanozan.evritext.settings.SubtitlePosition
import tr.com.uslanozan.evritext.settings.SubtitleSize
import tr.com.uslanozan.evritext.settings.applySubtitleAppearance
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
    private lateinit var pairingStore: PairingStore
    private var paired = false
    private var previewAppearance: SubtitleAppearance? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        pairingStore = PairingStore(this)
        paired = pairingStore.load() != null
        binding.rowEnabled.switchTitle.setText(R.string.setting_enabled)
        binding.rowEnabled.switchSummary.setText(R.string.setting_enabled_summary)
        binding.rowEnabled.root.setOnClickListener { settings.toggle() }
        binding.rowPairing.bind(R.string.setting_pairing, getString(R.string.setting_pairing_empty))
        binding.rowPairing.root.setOnClickListener { showPairingDialog() }
        binding.rowApiKey.bind(R.string.setting_api_key, getString(R.string.setting_api_key_empty))
        binding.rowApiKey.root.setOnClickListener { showApiKeyDialog() }
        binding.rowSubtitleColor.bind(R.string.setting_subtitle_color, "")
        binding.rowSubtitleColor.root.setOnClickListener { showColorDialog() }
        binding.rowSubtitleSize.bind(R.string.setting_subtitle_size, "")
        binding.rowSubtitleSize.root.setOnClickListener { showSizeDialog() }
        binding.rowSubtitleBackground.bind(R.string.setting_subtitle_background, "")
        binding.rowSubtitleBackground.root.setOnClickListener { showBackgroundDialog() }
        binding.rowSubtitlePosition.bind(R.string.setting_subtitle_position, "")
        binding.rowSubtitlePosition.root.setOnClickListener { showPositionDialog() }
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

    override fun onStart() {
        super.onStart()
        EvriService.setSettingsVisible(this, true)
    }

    override fun onStop() {
        EvriService.setSettingsVisible(this, false)
        super.onStop()
    }

    private suspend fun renderLoop() {
        while (currentCoroutineContext().isActive) {
            val session = EvriService.current
            // A disconnected tracker only contains the last playback anchor. Showing
            // it as live data makes the position and anchor age appear to keep moving
            // after subtitles have been switched off.
            val tracker = session
                ?.takeIf { it.status.value == LoungeSession.Status.CONNECTED }
                ?.tracker
            val prediction = tracker?.predict()

            val on = settings.enabled.value
            val hasApiKey = settings.apiKey.value != null
            val appearance = settings.subtitleAppearance()
            // Driven from the setting rather than from the tap, so the switch is right
            // even when something else flips it — the shortcut, or another screen.
            if (binding.rowEnabled.switchToggle.isChecked != on) {
                binding.rowEnabled.switchToggle.isChecked = on
            }

            binding.rowApiKey.rowValue.setText(
                if (hasApiKey) R.string.setting_api_key_set else R.string.setting_api_key_empty,
            )
            binding.rowPairing.rowValue.setText(
                if (paired) R.string.setting_pairing_set else R.string.setting_pairing_empty,
            )
            binding.rowSubtitleColor.rowValue.setText(colorLabel(appearance.color))
            binding.rowSubtitleSize.rowValue.setText(sizeLabel(appearance.size))
            binding.rowSubtitleBackground.rowValue.setText(backgroundLabel(appearance.background))
            binding.rowSubtitlePosition.rowValue.setText(positionLabel(appearance.position))
            if (appearance != previewAppearance) {
                binding.subtitlePreview.applySubtitleAppearance(appearance)
                previewAppearance = appearance
            }

            binding.statusLine.text = when {
                !on -> getString(R.string.setting_enabled_hint)
                !paired -> getString(R.string.setting_pairing_empty)
                !hasApiKey -> getString(R.string.status_api_key_required)
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

    private fun showColorDialog() {
        val values = SubtitleColor.entries
        showChoiceDialog(
            R.string.setting_subtitle_color,
            values.map(::colorLabel),
            values.indexOf(settings.subtitleColor.value),
            binding.rowSubtitleColor.root,
        ) { settings.setSubtitleColor(values[it]) }
    }

    private fun showSizeDialog() {
        val values = SubtitleSize.entries
        showChoiceDialog(
            R.string.setting_subtitle_size,
            values.map(::sizeLabel),
            values.indexOf(settings.subtitleSize.value),
            binding.rowSubtitleSize.root,
        ) { settings.setSubtitleSize(values[it]) }
    }

    private fun showBackgroundDialog() {
        val values = SubtitleBackground.entries
        showChoiceDialog(
            R.string.setting_subtitle_background,
            values.map(::backgroundLabel),
            values.indexOf(settings.subtitleBackground.value),
            binding.rowSubtitleBackground.root,
        ) { settings.setSubtitleBackground(values[it]) }
    }

    private fun showPositionDialog() {
        val values = SubtitlePosition.entries
        showChoiceDialog(
            R.string.setting_subtitle_position,
            values.map(::positionLabel),
            values.indexOf(settings.subtitlePosition.value),
            binding.rowSubtitlePosition.root,
        ) { settings.setSubtitlePosition(values[it]) }
    }

    private fun showChoiceDialog(
        titleRes: Int,
        labelResources: List<Int>,
        selected: Int,
        focusAfter: View,
        onSelected: (Int) -> Unit,
    ) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(
                labelResources.map(::getString).toTypedArray(),
                selected,
            ) { openDialog, which ->
                onSelected(which)
                openDialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { focusAfter.requestFocus() }
        dialog.show()
    }

    private fun colorLabel(value: SubtitleColor) = when (value) {
        SubtitleColor.WHITE -> R.string.subtitle_color_white
        SubtitleColor.YELLOW -> R.string.subtitle_color_yellow
        SubtitleColor.CYAN -> R.string.subtitle_color_cyan
        SubtitleColor.GREEN -> R.string.subtitle_color_green
        SubtitleColor.PINK -> R.string.subtitle_color_pink
        SubtitleColor.ORANGE -> R.string.subtitle_color_orange
    }

    private fun sizeLabel(value: SubtitleSize) = when (value) {
        SubtitleSize.SMALL -> R.string.subtitle_size_small
        SubtitleSize.MEDIUM -> R.string.subtitle_size_medium
        SubtitleSize.LARGE -> R.string.subtitle_size_large
        SubtitleSize.EXTRA_LARGE -> R.string.subtitle_size_extra_large
    }

    private fun backgroundLabel(value: SubtitleBackground) = when (value) {
        SubtitleBackground.OFF -> R.string.subtitle_background_off
        SubtitleBackground.LIGHT -> R.string.subtitle_background_light
        SubtitleBackground.NORMAL -> R.string.subtitle_background_normal
        SubtitleBackground.DARK -> R.string.subtitle_background_dark
    }

    private fun positionLabel(value: SubtitlePosition) = when (value) {
        SubtitlePosition.BOTTOM -> R.string.subtitle_position_bottom
        SubtitlePosition.RAISED -> R.string.subtitle_position_raised
        SubtitlePosition.HIGH -> R.string.subtitle_position_high
    }

    private fun showPairingDialog() {
        if (paired) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pairing_remove_title)
                .setMessage(R.string.pairing_remove_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.action_remove) { _, _ ->
                    pairingStore.clear()
                    paired = false
                    settings.setEnabled(false)
                    EvriService.reloadPairing(this)
                }
                .setPositiveButton(R.string.action_pair) { _, _ -> showPairingCodeDialog() }
                .show()
            return
        }
        showPairingCodeDialog()
    }

    private fun showPairingCodeDialog() {
        val input = EditText(this).apply {
            setSingleLine()
            hint = getString(R.string.pairing_input_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(PAIRING_CODE_LENGTH))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pairing_title)
            .setMessage(R.string.pairing_hint)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_pair, null)
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val code = input.text?.toString()?.filter(Char::isDigit).orEmpty()
                if (code.length != PAIRING_CODE_LENGTH) {
                    input.error = getString(R.string.pairing_code_invalid)
                    return@setOnClickListener
                }
                input.isEnabled = false
                button.isEnabled = false
                button.setText(R.string.pairing_in_progress)
                lifecycleScope.launch {
                    val client = LoungeClient(deviceName = getString(R.string.app_name))
                    val pairingSucceeded = runCatching { client.pair(code) }.getOrDefault(false)
                    when {
                        !pairingSucceeded -> {
                            input.error = getString(R.string.pairing_failed)
                            input.isEnabled = true
                            button.isEnabled = true
                            button.setText(R.string.action_retry)
                        }
                        !pairingStore.save(client.auth) -> {
                            input.error = getString(R.string.pairing_save_failed)
                            input.isEnabled = true
                            button.isEnabled = true
                            button.setText(R.string.action_retry)
                        }
                        else -> {
                            paired = true
                            EvriService.reloadPairing(this@MainActivity)
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener { binding.rowPairing.root.requestFocus() }
        dialog.show()
        input.requestFocus()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            setSingleLine()
            hint = getString(R.string.api_key_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.api_key_dialog_title)
            .setMessage(R.string.api_key_dialog_message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_save, null)

        if (settings.apiKey.value != null) {
            builder.setNeutralButton(R.string.action_remove, null)
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isEmpty()) {
                    input.error = getString(R.string.api_key_required)
                } else {
                    settings.setApiKey(value)
                    dialog.dismiss()
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                settings.clearApiKey()
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { binding.rowApiKey.root.requestFocus() }
        dialog.show()
        input.requestFocus()
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

    private companion object {
        const val PAIRING_CODE_LENGTH = 12
    }
}
