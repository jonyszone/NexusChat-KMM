package shafi.example.nexuschat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.nexuschat.data.network.CredentialPayload
import com.example.nexuschat.domain.messenger.MessengerSession
import com.example.nexuschat.domain.messenger.MessengerSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidMessengerSessionStore internal constructor(context: Context, private val namespace: String) : MessengerSessionStore {
    constructor(context: Context) : this(context, "nexuschat_messenger_session")

    private val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val associatedData = namespace.toByteArray(Charsets.UTF_8)

    override suspend fun load(): MessengerSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val encrypted = preferences.getString("session", null) ?: return@withLock null
            try {
                val payload = checkNotNull(CredentialPayload.decode(Base64.decode(encrypted, Base64.NO_WRAP)))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload.iv))
                cipher.updateAAD(associatedData)
                Json.decodeFromString<MessengerSession>(cipher.doFinal(payload.ciphertext).toString(Charsets.UTF_8))
            } catch (_: Exception) { throw IllegalStateException("Saved messenger session could not be decrypted") }
        }
    }

    override suspend fun save(session: MessengerSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(associatedData)
            val encrypted = cipher.doFinal(Json.encodeToString(MessengerSession.serializer(), session).toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(CredentialPayload.encode(cipher.iv, encrypted), Base64.NO_WRAP)
            check(preferences.edit().putString("session", value).commit()) { "Unable to save messenger session" }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { check(preferences.edit().remove("session").commit()) { "Unable to clear messenger session" } }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(namespace, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(namespace, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
