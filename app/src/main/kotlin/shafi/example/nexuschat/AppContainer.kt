package shafi.example.nexuschat

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.nexuschat.data.demo.DemoChatRepository
import com.example.nexuschat.data.network.KtorLlmStreamingClient
import com.example.nexuschat.db.NexusChatDatabase
import com.example.nexuschat.domain.repository.ModeAwareChatRepository
import com.example.nexuschat.domain.repository.OfflineFirstChatRepository
import com.example.nexuschat.domain.repository.SqlDelightChatStorage
import com.example.nexuschat.presentation.ChatViewModel
import com.example.nexuschat.presentation.ChatSavedState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Application-scoped composition root. Holds the HTTP client and SQLDelight
 * driver for the process lifetime so they are never recreated on
 * recomposition, and exposes a factory for lifecycle-owned ViewModels.
 */
class AppContainer(context: Context) {
    val apiKeyStore = AndroidApiKeyStore(context.applicationContext)
    val modeStore = AndroidChatModeStore(context.applicationContext)

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    private val databaseDriver = AndroidSqliteDriver(
            schema = NexusChatDatabase.Schema,
            context = context.applicationContext,
            name = "nexuschat.db",
            callback = object : AndroidSqliteDriver.Callback(NexusChatDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // Enforce the message -> session foreign key for every connection.
                    db.setForeignKeyConstraintsEnabled(true)
                }
            }
        )
    private val database = NexusChatDatabase(databaseDriver)

    private val demoRepository = DemoChatRepository()

    private val realRepository = OfflineFirstChatRepository(
        llm = KtorLlmStreamingClient(httpClient, apiKeyStore),
        storage = SqlDelightChatStorage(database),
        mode = com.example.nexuschat.data.model.ChatMode.BYOK
    )

    private val repository = ModeAwareChatRepository(
        modeStore = modeStore,
        real = realRepository,
        demo = demoRepository
    )

    fun createChatViewModel(savedState: ChatSavedState? = null): ChatViewModel = ChatViewModel(repository, modeStore, savedState)

    /** Only call when the owning process/application is actually going away. */
    fun close() {
        httpClient.close()
        databaseDriver.close()
    }
}
