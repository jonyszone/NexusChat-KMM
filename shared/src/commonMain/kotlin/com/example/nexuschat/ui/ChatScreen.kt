package com.example.nexuschat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.AvailableModels
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.presentation.ChatEvent
import com.example.nexuschat.presentation.ChatUiState
import com.example.nexuschat.presentation.ChatViewModel

/**
 * Entry point bound to a shared ViewModel.
 * Platform apps construct the ViewModel with their HttpClient +
 * SecureKeyStore + SQLDelight driver, then call ChatScreen(viewModel).
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit = {}
) {
    val state by viewModel.ui.collectAsState()
    ChatContent(
        state = state,
        onSend = viewModel::send,
        onCancel = viewModel::cancel,
        onSwitchModel = viewModel::switchModel,
        onConsumeEvent = viewModel::consumeEvent,
        onOpenSettings = onOpenSettings
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onSwitchModel: (AiModel) -> Unit,
    onConsumeEvent: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Surface BYOK / stream errors once.
    LaunchedEffect(state.lastEvent) {
        when (val e = state.lastEvent) {
            is ChatEvent.NeedsApiKey -> {
                snackbar.showSnackbar("Missing ${e.providerName} key — open Settings to add (BYOK).")
                onConsumeEvent()
            }
            is ChatEvent.StreamFailed -> {
                snackbar.showSnackbar(e.message)
                onConsumeEvent()
            }
            null -> Unit
        }
    }

    // Autoscroll on new message / new token.
    LaunchedEffect(state.messages.size, state.streamingText.length) {
        val total = state.messages.size + if (state.streamingText.isNotEmpty()) 1 else 0
        if (total > 0) listState.animateScrollToItem(maxOf(0, total - 1))
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("NexusChat") },
                actions = {
                    ModelSwitcher(
                        current = state.currentModel,
                        onSelect = onSwitchModel
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            InputBar(
                value = input,
                onValue = { input = it },
                isStreaming = state.isStreaming,
                onSend = {
                    onSend(input)
                    input = ""
                },
                onCancel = onCancel
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (state.streamingText.isNotEmpty()) {
                item(key = "__streaming__") {
                    StreamingBubble(state.streamingText, state.currentModel.displayName)
                }
            }
            if (state.isStreaming && state.streamingText.isEmpty()) {
                item(key = "__thinking__") {
                    AssistChip(onClick = {}, label = { Text(" thinking… ") })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSwitcher(current: AiModel, onSelect: (AiModel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.padding(end = 8.dp)
    ) {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(current.displayName, maxLines = 1) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().widthIn(max = 220.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AvailableModels.all.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName) },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            tonalElevation = if (isUser) 3.dp else 1.dp,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                if (!isUser && msg.modelId != null) {
                    Text(msg.modelId, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                }
                MarkdownLite(msg.content)
                CopyButton(msg.content)
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, modelName: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(modelName, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    // Blinking cursor gives smooth streaming affordance.
                    Text(text + "▍", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Minimal markdown: ```fenced code blocks``` get a monospace surface,
 * everything else renders as selectable plain text. Swap for
 * mikepenz/multiplatform-markdown-renderer when rich styling is needed.
 */
@Composable
private fun MarkdownLite(content: String) {
    val parts = remember(content) { content.split("```") }
    if (parts.size == 1) {
        SelectionContainer { Text(parts[0], style = MaterialTheme.typography.bodyMedium) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 0) {
                if (part.isNotEmpty()) {
                    SelectionContainer { Text(part, style = MaterialTheme.typography.bodyMedium) }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            part.trim('\n'),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValue: (String) -> Unit,
    isStreaming: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message…") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp)
            )
            if (isStreaming) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

// Preview-friendly overload without ViewModel / icons dependency issues.
@Composable
private fun ClosePreviewHint() {
    Icon(Icons.Default.Close, contentDescription = null)
}
