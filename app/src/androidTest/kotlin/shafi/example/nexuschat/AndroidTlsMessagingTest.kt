package shafi.example.nexuschat

import androidx.test.platform.app.InstrumentationRegistry
import com.example.nexuschat.data.network.KtorMessengerUpdates
import com.example.nexuschat.data.network.RustMessagingApi
import com.example.nexuschat.domain.messenger.MessengerSession
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.net.ssl.SSLException

class AndroidTlsMessagingTest {
    @Test
    fun untrustedCertificatesAndWrongHostnamesAreRejectedForHttpAndWebsockets() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val server = arguments.getString("messagingServer")
        assumeTrue("Provide an HTTPS fixture and messagingCa", server?.startsWith("https://") == true && arguments.getString("messagingCa") != null)
        val untrusted = testMessagingClient(trustTestCa = false)
        val mismatched = testMessagingClient(mismatchedHost = true)
        try {
            expectTlsFailure { untrusted.get("$server/health") }
            expectTlsFailure {
                KtorMessengerUpdates(untrusted).events(MessengerSession(server!!, "test", "test", "test-only-token", Long.MAX_VALUE)).first()
            }
            val wrongHost = URLBuilder(server!!).apply { host = "mismatch.example.test" }.buildString()
            expectTlsFailure { mismatched.get("$wrongHost/health") }
            expectTlsFailure {
                KtorMessengerUpdates(mismatched).events(MessengerSession(wrongHost, "test", "test", "test-only-token", Long.MAX_VALUE)).first()
            }
            try {
                RustMessagingApi(untrusted).normalizeServer("http://127.0.0.1:8443")
                fail("Strict messaging transport must reject HTTP")
            } catch (_: IllegalArgumentException) { }
        } finally {
            untrusted.close()
            mismatched.close()
        }
    }

    private suspend fun expectTlsFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected certificate validation to fail")
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            assertTrue("Expected a TLS verification error, not an unrelated network failure",
                generateSequence<Throwable>(cause) { it.cause }.any { it is SSLException })
        }
    }
}
