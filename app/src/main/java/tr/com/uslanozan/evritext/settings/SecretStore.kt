package tr.com.uslanozan.evritext.settings

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small Android Keystore-backed store for one provider credential. */
internal class SecretStore(
    private val prefs: SharedPreferences,
    namespace: String? = null,
) {

    // Gemini keeps the original names so existing installs retain their saved key.
    private val ivPreference = namespace?.let { "${KEY_IV}_$it" } ?: KEY_IV
    private val ciphertextPreference =
        namespace?.let { "${KEY_CIPHERTEXT}_$it" } ?: KEY_CIPHERTEXT

    fun read(): String? = runCatching {
        val ciphertext = prefs.getString(ciphertextPreference, null) ?: return null
        val iv = prefs.getString(ivPreference, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
        )
        String(
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
            Charsets.UTF_8,
        ).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun write(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(ivPreference, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ciphertextPreference, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(ivPreference).remove(ciphertextPreference).apply()
    }

    fun owns(key: String?): Boolean = key == ivPreference || key == ciphertextPreference

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "evritext.provider.api-key"
        private const val KEY_IV = "provider_api_key_iv"
        private const val KEY_CIPHERTEXT = "provider_api_key_ciphertext"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
