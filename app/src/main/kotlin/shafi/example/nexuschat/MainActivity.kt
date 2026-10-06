package shafi.example.nexuschat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.network.ApiKeyProvider
import com.example.nexuschat.data.network.KtorLlmStreamingClient
import com.example.nexuschat.domain.repository.InMemoryChatStorage
import com.example.nexuschat.domain.repository.OfflineFirstChatRepository
import com.example.nexuschat.presentation.ChatViewModel
import com.example.nexuschat.ui.ChatScreen
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel = remember { createChatViewModel() }
            ChatScreen(viewModel = viewModel)
        }
    }

    private fun createChatViewModel(): ChatViewModel {
        val httpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val keyProvider = object : ApiKeyProvider {
            override suspend fun keyFor(provider: LlmProvider): String? = null
        }
        return ChatViewModel(
            OfflineFirstChatRepository(
                llm = KtorLlmStreamingClient(http = httpClient, keys = keyProvider),
                storage = InMemoryChatStorage()
            )
        )
    }
}
