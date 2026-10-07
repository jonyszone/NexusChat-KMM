package shafi.example.nexuschat

import android.content.Context
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.domain.repository.ChatModeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted Demo/BYOK selection. Ordinary preferences (never a credential
 * vault) and an unencrypted mode string — keys stay in the Android Keystore.
 */
class AndroidChatModeStore(context: Context) : ChatModeStore {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readMode())
    override val mode: StateFlow<ChatMode> = _mode.asStateFlow()

    override suspend fun setMode(mode: ChatMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private fun readMode(): ChatMode =
        preferences.getString(KEY_MODE, null)
            ?.let { stored -> ChatMode.entries.firstOrNull { it.name == stored } }
            ?: ChatMode.DEMO

    private companion object {
        const val PREFERENCES_NAME = "nexuschat_settings"
        const val KEY_MODE = "chat_mode"
    }
}
