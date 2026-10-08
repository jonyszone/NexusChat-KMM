package shafi.example.nexuschat

import android.content.Context
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nexuschat.domain.messenger.MessengerSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class AndroidMessengerSessionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "messenger_session_test_${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val session = MessengerSession(
        "https://chat.example.test", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        "private-bearer-token", 4_000_000_000L,
    )

    @After
    fun cleanUp() {
        assertTrue(preferences.edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(namespace) }
    }

    @Test
    fun encryptedSessionSurvivesStoreRecreationAndClear() = runBlocking {
        val store = AndroidMessengerSessionStore(context, namespace)
        assertNull(store.load())
        store.save(session)
        val encoded = checkNotNull(preferences.getString("session", null))
        val persisted = Base64.decode(encoded, Base64.NO_WRAP).toString(Charsets.UTF_8)
        assertFalse(persisted.contains(session.accessToken))
        assertFalse(persisted.contains(session.accountId))
        assertEquals(session, AndroidMessengerSessionStore(context, namespace).load())
        store.clear()
        assertFalse(preferences.contains("session"))
        assertNull(AndroidMessengerSessionStore(context, namespace).load())
    }

    @Test
    fun tamperedCiphertextIsRejectedWithoutExposingCredentials() = runBlocking {
        val store = AndroidMessengerSessionStore(context, namespace)
        store.save(session)
        val bytes = Base64.decode(preferences.getString("session", null), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(preferences.edit().putString("session", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
        try {
            store.load()
            fail("Tampered session must not decrypt")
        } catch (cause: IllegalStateException) {
            assertEquals("Saved messenger session could not be decrypted", cause.message)
            assertNull(cause.cause)
            assertFalse(cause.toString().contains(session.accessToken))
        }
        store.clear()
        store.save(session)
        assertEquals(session, store.load())
    }

    @Test
    fun repeatedWritesUseFreshCiphertextAndReplaceTheSession() = runBlocking {
        val store = AndroidMessengerSessionStore(context, namespace)
        store.save(session)
        val first = preferences.getString("session", null)
        store.save(session)
        assertNotEquals(first, preferences.getString("session", null))
        val replacement = session.copy(accessToken = "replacement-token", sessionId = UUID.randomUUID().toString())
        store.save(replacement)
        assertEquals(replacement, AndroidMessengerSessionStore(context, namespace).load())
    }
}
