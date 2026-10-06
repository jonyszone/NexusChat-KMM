package shafi.example.nexuschat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.example.nexuschat.data.demo.DemoChatRepository
import com.example.nexuschat.presentation.ChatViewModel
import com.example.nexuschat.ui.NexusApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel = remember { createChatViewModel() }
            NexusApp(viewModel = viewModel)
        }
    }

    private fun createChatViewModel(): ChatViewModel = ChatViewModel(DemoChatRepository())
}
