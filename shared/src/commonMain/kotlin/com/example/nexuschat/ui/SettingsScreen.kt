package com.example.nexuschat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.network.ApiKeyStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    keyStore: ApiKeyStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf(emptyMap<LlmProvider, String>()) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(keyStore) {
        values = LlmProvider.entries.associateWith { keyStore.keyFor(it).orEmpty() }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        }
        Text("BYOK provider keys", style = MaterialTheme.typography.titleLarge)
        Text(
            "Keys are encrypted with Android Keystore and used only on this device. Leave a field empty to remove it.",
            style = MaterialTheme.typography.bodyMedium
        )
        LlmProvider.entries.forEach { provider ->
            OutlinedTextField(
                value = values[provider].orEmpty(),
                onValueChange = { value ->
                    saved = false
                    values = values + (provider to value)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(providerLabel(provider)) },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                scope.launch {
                    values.forEach { (provider, value) -> keyStore.setKey(provider, value) }
                    saved = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saved) "Saved" else "Save keys")
        }
    }
}

private fun providerLabel(provider: LlmProvider): String = when (provider) {
    LlmProvider.OPENAI -> "OpenAI API key"
    LlmProvider.ANTHROPIC -> "Anthropic API key"
    LlmProvider.GEMINI -> "Gemini API key"
    LlmProvider.DEEPSEEK -> "DeepSeek API key"
}
