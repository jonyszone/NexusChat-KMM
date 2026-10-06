package shafi.example.nexuschat

import android.content.Context
import com.example.nexuschat.data.demo.DemoChatRepository
import com.example.nexuschat.data.network.KtorLlmStreamingClient
import com.example.nexuschat.domain.repository.InMemoryChatStorage
import com.example.nexuschat.domain.repository.KeyAwareChatRepository
import com.example.nexuschat.domain.repository.OfflineFirstChatRepository
import com.example.nexuschat.presentation.ChatViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Android composition root for demo mode and production-ready BYOK wiring. */
class AppContainer(context: Context) {
    val apiKeyStore = AndroidApiKeyStore(context.applicationContext)

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    private val demoRepository = DemoChatRepository()
    private val realRepository = OfflineFirstChatRepository(
        llm = KtorLlmStreamingClient(httpClient, apiKeyStore),
        storage = InMemoryChatStorage()
    )

    val chatViewModel = ChatViewModel(
        repository = KeyAwareChatRepository(
            real = realRepository,
            demo = demoRepository,
            keys = apiKeyStore
        )
    )
}
