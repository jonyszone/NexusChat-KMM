package shafi.example.nexuschat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.example.nexuschat.ui.NexusApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val container = remember { AppContainer(applicationContext) }
            NexusApp(viewModel = container.chatViewModel, apiKeyStore = container.apiKeyStore)
        }
    }
}
