package tr.com.uslanozan.evritext.ui

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
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
            val hasApiKey = settings.apiKey.value != null
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
