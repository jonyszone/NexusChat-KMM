package com.example.nexuschat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.network.ApiKeyStore
import kotlinx.coroutines.launch

private val SettingsGreen = Color(0xFF008069)
private val SettingsMuted = Color(0xFF667781)

@Composable
fun SettingsScreen(
    keyStore: ApiKeyStore,
    mode: ChatMode,
    onModeChange: (ChatMode) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // Only whether a key is configured is held in UI state — never the key itself.
    var configured by remember { mutableStateOf(emptySet<LlmProvider>()) }
    // Blank replacement fields; cleared after save so decrypted keys never persist in memory.
    var inputs by remember { mutableStateOf(emptyMap<LlmProvider, String>()) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun refreshConfigured() {
        configured = LlmProvider.entries
            .filter { !keyStore.keyFor(it).isNullOrBlank() }
            .toSet()
    }

    LaunchedEffect(keyStore) { refreshConfigured() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Text("Chat mode", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ChatMode.DEMO,
                onClick = { onModeChange(ChatMode.DEMO) },
                label = { Text("Demo") }
            )
            FilterChip(
                selected = mode == ChatMode.BYOK,
                onClick = { onModeChange(ChatMode.BYOK) },
                label = { Text("BYOK") }
            )
        }
        Text(
            if (mode == ChatMode.DEMO) {
                "Demo mode uses local sample data and never contacts a provider."
            } else {
                "BYOK mode uses your own provider key. Missing keys show configuration guidance instead of a demo answer."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SettingsMuted
        )

        HorizontalDivider()
        Text("BYOK provider keys", style = MaterialTheme.typography.titleLarge)
        Text(
            "Keys are encrypted with the Android Keystore and used only on this device. Enter a new value to replace an existing key, or remove it.",
            style = MaterialTheme.typography.bodyMedium
        )

        LlmProvider.entries.forEach { provider ->
            val isConfigured = provider in configured
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isConfigured) SettingsGreen else SettingsMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${providerLabel(provider)} — ${if (isConfigured) "configured" else "not set"}",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = inputs[provider].orEmpty(),
                    onValueChange = { value ->
                        status = null
                        inputs = inputs + (provider to value)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New ${providerLabel(provider)} (blank keeps current)") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val value = inputs[provider].orEmpty().trim()
                            if (value.isEmpty()) return@Button
                            scope.launch {
                                keyStore.setKey(provider, value)
                                inputs = inputs + (provider to "")
                                refreshConfigured()
                                status = "${providerLabel(provider)} saved"
                            }
                        },
                        enabled = !inputs[provider].orEmpty().isBlank()
                    ) { Text("Save") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                keyStore.clearKey(provider)
                                inputs = inputs + (provider to "")
                                refreshConfigured()
                                status = "${providerLabel(provider)} removed"
                            }
                        },
                        enabled = isConfigured
                    ) { Text("Remove") }
                }
            }
        }

        status?.let {
            Text(it, color = SettingsGreen, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Privacy", style = MaterialTheme.typography.titleLarge)
        Text(
            "Chat history is stored unencrypted in this app's private SQLite database on this device. " +
                "In BYOK mode, message content and the conversation history are transmitted to the " +
                "provider you selected; there is no end-to-end encryption. Keys are never uploaded and " +
                "there is no cloud sync or backup of keys or history.",
            style = MaterialTheme.typography.bodySmall,
            color = SettingsMuted
        )
    }
}

private fun providerLabel(provider: LlmProvider): String = when (provider) {
    LlmProvider.OPENAI -> "OpenAI key"
    LlmProvider.ANTHROPIC -> "Anthropic key"
    LlmProvider.GEMINI -> "Gemini key"
    LlmProvider.DEEPSEEK -> "DeepSeek key"
}
