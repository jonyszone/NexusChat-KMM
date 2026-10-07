package shafi.example.nexuschat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.nexuschat.presentation.ChatSavedState
import com.example.nexuschat.presentation.ChatViewModel
import com.example.nexuschat.ui.NexusApp

class MainActivity : ComponentActivity() {
    private var handleBack: () -> Boolean = { false }
    private val container: AppContainer
        get() = (application as NexusChatApplication).container

    private class SavedStateAdapter(private val handle: SavedStateHandle) : ChatSavedState {
        override fun getString(key: String): String? = handle.get<String>(key)
        override fun setString(key: String, value: String?) {
            if (value == null) handle.remove<String>(key) else handle[key] = value
        }
    }

    private val viewModel: ChatViewModel by lazy {
        val factory = object : AbstractSavedStateViewModelFactory(this, intent?.extras) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                container.createChatViewModel(SavedStateAdapter(handle)) as T
        }
        ViewModelProvider(this, factory)[ChatViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBack()) finish()
            }
        })
        setContent {
            NexusApp(
                viewModel = viewModel,
                apiKeyStore = container.apiKeyStore,
                modeStore = container.modeStore
                ,onRegisterBackHandler = { handleBack = it }
            )
        }
    }
}
