package shafi.example.nexuschat

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.network.ApiKeyStore
import com.example.nexuschat.data.network.CredentialCorruptedException
import com.example.nexuschat.data.network.CredentialPayload
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed BYOK storage. Plaintext keys never enter
 * SharedPreferences or the UI. Crypto work runs off the main thread and is
 * serialized so concurrent set/read/replace cannot interleave mid-operation.
 *
 * A stored-but-undecryptable value raises [CredentialCorruptedException] rather
 * than being reported as a missing key, and a failed durable write is surfaced
 * instead of being shown as "Saved".
 */
class AndroidApiKeyStore(context: Context) : ApiKeyStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun keyFor(provider: LlmProvider): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = preferences.getString(provider.name, null) ?: return@withLock null
            val payload = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull()
                ?: throw CredentialCorruptedException(provider)
            val decoded = CredentialPayload.decode(payload)
                ?: throw CredentialCorruptedException(provider)
            try {
                decrypt(provider, decoded)
            } catch (e: Exception) {
                throw CredentialCorruptedException(provider, e)
            }
        }
    }

    override suspend fun setKey(provider: LlmProvider, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            clearKey(provider)
            return
        }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val encoded = Base64.encodeToString(encrypt(trimmed), Base64.NO_WRAP)
                val committed = preferences.edit().putString(provider.name, encoded).commit()
                check(committed) { "Failed to persist ${provider.name} key" }
            }
        }
    }

    override suspend fun clearKey(provider: LlmProvider) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                preferences.edit().remove(provider.name).commit()
            }
        }
    }

    private fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return CredentialPayload.encode(cipher.iv, encrypted)
    }

    private fun decrypt(provider: LlmProvider, decoded: CredentialPayload.Decoded): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, decoded.iv)
        )
        return cipher.doFinal(decoded.ciphertext).toString(Charsets.UTF_8)
    }

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
