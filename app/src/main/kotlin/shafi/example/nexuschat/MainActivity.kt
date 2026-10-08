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
import com.example.nexuschat.ui.MessengerScreen
import com.example.nexuschat.presentation.MessengerViewModel
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

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

    private val factory by lazy {
        object : AbstractSavedStateViewModelFactory(this, intent?.extras) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                when (modelClass) {
                    ChatViewModel::class.java -> container.createChatViewModel(SavedStateAdapter(handle))
                    MessengerViewModel::class.java -> container.createMessengerViewModel(SavedStateAdapter(handle))
                    else -> error("Unsupported ViewModel")
                } as T
        }
    }
    private val viewModel: ChatViewModel by lazy { ViewModelProvider(this, factory)[ChatViewModel::class.java] }
    private val messenger: MessengerViewModel by lazy { ViewModelProvider(this, factory)[MessengerViewModel::class.java] }

    override fun onStart() {
        super.onStart()
        messenger.setForeground(true)
    }

    override fun onStop() {
        messenger.setForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBack()) finish()
            }
        })
        setContent {
            var assistant by rememberSaveable { mutableStateOf(false) }
            if (assistant) NexusApp(
                viewModel = viewModel,
                apiKeyStore = container.apiKeyStore,
                modeStore = container.modeStore,
                onRegisterBackHandler = { back -> handleBack = { if (back()) true else { assistant = false; true } } }
            )
            else {
                SideEffect { handleBack = messenger::back }
                MessengerScreen(messenger, defaultServer = if (BuildConfig.DEBUG) "http://10.0.2.2:3000" else "", onOpenAssistant = { assistant = true })
            }
        }
    }
}
