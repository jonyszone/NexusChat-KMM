package shafi.example.nexuschat

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.network.ApiKeyStore
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Android Keystore-backed BYOK storage. Plaintext keys never enter SharedPreferences. */
class AndroidApiKeyStore(context: Context) : ApiKeyStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun keyFor(provider: LlmProvider): String? =
        preferences.getString(provider.name, null)?.let(::decrypt)

    override suspend fun setKey(provider: LlmProvider, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            clearKey(provider)
        } else {
            preferences.edit().putString(provider.name, encrypt(trimmed)).apply()
        }
    }

    override suspend fun clearKey(provider: LlmProvider) {
        preferences.edit().remove(provider.name).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            ByteBuffer.allocate(4 + iv.size + encrypted.size)
                .putInt(iv.size)
                .put(iv)
                .put(encrypted)
                .array(),
            Base64.NO_WRAP
        )
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(payload)
        val iv = ByteArray(buffer.int).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "nexuschat_secure_keys"
        const val KEY_ALIAS = "nexuschat_api_keys"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
