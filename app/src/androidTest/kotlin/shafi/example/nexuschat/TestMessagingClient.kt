package shafi.example.nexuschat

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.Dns
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// Only instrumentation clients trust the ephemeral public test CA. Production
// clients keep platform trust and the default hostname verifier unchanged.
internal fun testMessagingClient(trustTestCa: Boolean = true, mismatchedHost: Boolean = false): HttpClient {
    val encoded = InstrumentationRegistry.getArguments().getString("messagingCa")
    val trust = if (trustTestCa && encoded != null) {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.decode(encoded, Base64.NO_WRAP).inputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("ephemeral-messaging-test-ca", certificate)
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        ssl.socketFactory to manager
    } else null
    return HttpClient(OkHttp) {
        followRedirects = false
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        engine {
            config {
                trust?.let { sslSocketFactory(it.first, it.second) }
                if (mismatchedHost) dns { hostname ->
                    if (hostname == "mismatch.example.test") listOf(InetAddress.getByName("127.0.0.1"))
                    else Dns.SYSTEM.lookup(hostname)
                }
            }
        }
    }
}
