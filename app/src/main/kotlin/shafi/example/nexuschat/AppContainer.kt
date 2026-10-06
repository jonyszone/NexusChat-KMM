package shafi.example.nexuschat

import android.content.Context
import com.example.nexuschat.data.demo.DemoChatRepository
import com.example.nexuschat.data.network.KtorLlmStreamingClient
import com.example.nexuschat.domain.repository.KeyAwareChatRepository
import com.example.nexuschat.domain.repository.OfflineFirstChatRepository
import com.example.nexuschat.domain.repository.SqlDelightChatStorage
import com.example.nexuschat.presentation.ChatViewModel
import com.example.nexuschat.db.NexusChatDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
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
    private val database = NexusChatDatabase(
        AndroidSqliteDriver(
            schema = NexusChatDatabase.Schema,
            context = context.applicationContext,
            name = "nexuschat.db"
        )
    )
    private val realRepository = OfflineFirstChatRepository(
        llm = KtorLlmStreamingClient(httpClient, apiKeyStore),
        storage = SqlDelightChatStorage(database)
    )

    val chatViewModel = ChatViewModel(
        repository = KeyAwareChatRepository(
            real = realRepository,
            demo = demoRepository,
            keys = apiKeyStore
        )
    )
}
