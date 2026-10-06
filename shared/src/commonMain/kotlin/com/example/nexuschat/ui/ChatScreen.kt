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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.AvailableModels
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.presentation.ChatEvent
import com.example.nexuschat.presentation.ChatUiState
import com.example.nexuschat.presentation.ChatViewModel

private val WhatsAppGreen = Color(0xFF008069)
private val WhatsAppLightGreen = Color(0xFFD9FDD3)
private val WhatsAppDarkGreen = Color(0xFF005C4B)
private val WhatsAppChatWallpaper = Color(0xFFEFEAE2)
private val WhatsAppIncoming = Color(0xFFFFFFFF)
private val WhatsAppText = Color(0xFF111B21)
private val WhatsAppMuted = Color(0xFF667781)
private val WhatsAppDivider = Color(0xFFE9EDEF)

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
        containerColor = WhatsAppChatWallpaper,
        topBar = {
            WhatsAppHeader(
                currentModel = state.currentModel,
                isStreaming = state.isStreaming,
                onSwitchModel = onSwitchModel,
                onOpenSettings = onOpenSettings
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
        ChatWallpaper(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.messages.isEmpty() && state.streamingText.isEmpty()) {
                    item(key = "__empty__") { EmptyChatIntro(state.currentModel.displayName) }
                }
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
                if (state.streamingText.isNotEmpty()) {
                    item(key = "__streaming__") {
                        StreamingBubble(state.streamingText, state.currentModel.displayName)
                    }
                }
                if (state.isStreaming && state.streamingText.isEmpty()) {
                    item(key = "__thinking__") { TypingBubble(state.currentModel.displayName) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsAppHeader(
    currentModel: AiModel,
    isStreaming: Boolean,
    onSwitchModel: (AiModel) -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = WhatsAppText)
            }
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(WhatsAppGreen),
                contentAlignment = Alignment.Center
            ) {
                Text("N", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NexusChat",
                    color = WhatsAppText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isStreaming) "typing…" else "online • BYOK private chat",
                    color = WhatsAppMuted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ModelSwitcher(current = currentModel, onSelect = onSwitchModel)
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Lock, contentDescription = "Settings and privacy", tint = WhatsAppGreen)
            }
            IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = WhatsAppText)
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
        onExpandedChange = { expanded = !expanded }
    ) {
        AssistChip(
            onClick = { expanded = true },
            label = {
                Text(
                    current.displayName,
                    color = WhatsAppDarkGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .widthIn(max = 132.dp)
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
private fun ChatWallpaper(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(WhatsAppChatWallpaper)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            repeat(12) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    repeat(5) { col ->
                        val mark = when ((row + col) % 4) {
                            0 -> "✦"
                            1 -> "◌"
                            2 -> "♡"
                            else -> "⌁"
                        }
                        Text(mark, color = Color(0x1A667781), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        content()
    }
}

@Composable
private fun EmptyChatIntro(modelName: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = Color(0xFFFFF7D6),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 0.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = "Messages are private to this device. Start chatting with $modelName.",
                color = Color(0xFF54656F),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
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
            color = if (isUser) WhatsAppLightGreen else WhatsAppIncoming,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            shape = if (isUser) {
                RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            } else {
                RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            },
            modifier = Modifier.widthIn(max = 352.dp)
        ) {
            Column(Modifier.padding(start = 11.dp, top = 8.dp, end = 8.dp, bottom = 5.dp)) {
                if (!isUser && msg.modelId != null) {
                    Text(
                        msg.modelId,
                        color = WhatsAppGreen,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(3.dp))
                }
                MarkdownLite(msg.content)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CopyButton(msg.content)
                    Text("now", color = WhatsAppMuted, style = MaterialTheme.typography.labelSmall)
                    if (isUser) {
                        Spacer(Modifier.width(3.dp))
                        Text("✓✓", color = WhatsAppGreen, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, modelName: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = WhatsAppIncoming,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            modifier = Modifier.widthIn(max = 352.dp)
        ) {
            Column(Modifier.padding(11.dp)) {
                Text(modelName, color = WhatsAppGreen, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(text + "▍", color = WhatsAppText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TypingBubble(modelName: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = WhatsAppIncoming,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(modelName, color = WhatsAppGreen, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Text("typing…", color = WhatsAppMuted, style = MaterialTheme.typography.bodySmall)
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
        SelectionContainer { Text(parts[0], color = WhatsAppText, style = MaterialTheme.typography.bodyMedium) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 0) {
                if (part.isNotEmpty()) {
                    SelectionContainer { Text(part, color = WhatsAppText, style = MaterialTheme.typography.bodyMedium) }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFEEF1F0),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            part.trim('\n'),
                            color = WhatsAppText,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(9.dp)
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
    IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.size(32.dp)) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = "Copy",
            tint = WhatsAppMuted,
            modifier = Modifier.size(16.dp)
        )
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
    Surface(color = WhatsAppChatWallpaper, tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 1.dp,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = "Emoji", tint = WhatsAppMuted)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValue,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = WhatsAppText),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(WhatsAppGreen),
                        maxLines = 4,
                        decorationBox = { innerTextField ->
                            if (value.isEmpty()) Text("Message", color = WhatsAppMuted, style = MaterialTheme.typography.bodyLarge)
                            innerTextField()
                        }
                    )
                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = WhatsAppMuted)
                    }
                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = WhatsAppMuted)
                    }
                }
            }
            Spacer(Modifier.width(7.dp))
            Surface(
                color = WhatsAppGreen,
                shape = CircleShape,
                shadowElevation = 2.dp,
                modifier = Modifier.size(48.dp)
            ) {
                if (isStreaming) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                    }
                } else {
                    IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (value.isNotBlank()) Color.White else Color.White.copy(alpha = 0.45f)
                        )
                    }
                }
            }
        }
    }
}

// Keep a local reference to a subtle divider color for future chat-list work.
@Suppress("unused")
private val WhatsAppSubtleDivider = WhatsAppDivider
