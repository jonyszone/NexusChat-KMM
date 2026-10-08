package com.example.nexuschat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nexuschat.presentation.MessengerViewModel

@Composable
fun MessengerScreen(viewModel: MessengerViewModel, defaultServer: String, onOpenAssistant: () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF128C7E), secondary = Color(0xFF4D6875), tertiary = Color(0xFFB94E65))) {
        MessengerContent(viewModel, defaultServer, onOpenAssistant)
    }
}

@Composable
private fun MessengerContent(viewModel: MessengerViewModel, defaultServer: String, onOpenAssistant: () -> Unit) {
    val state by viewModel.ui.collectAsState()
    var showAccount by remember { mutableStateOf(false) }
    var newChat by remember { mutableStateOf(false) }
    var peer by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    val timeline = rememberLazyListState()

    LaunchedEffect(state.selectedId) { if (state.selectedId != null) newChat = false }
    LaunchedEffect(state.selectedId, state.messages.lastOrNull()?.id, state.pending.size) {
        val count = state.messages.size + state.pending.size
        if (count > 0) timeline.scrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.selectedId != null) IconButton(onClick = { viewModel.back() }, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Conversations") }
                    Text(if (state.selectedId == null) "NexusChat" else "Conversation", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenAssistant) { Icon(Icons.AutoMirrored.Filled.Chat, "Assistant") }
                    if (state.signedIn) {
                        IconButton(onClick = { viewModel.refresh() }, enabled = !state.busy) { Icon(Icons.Default.Refresh, "Sync messages") }
                        IconButton(onClick = { showAccount = true }) { Icon(Icons.Default.Person, "Account") }
                    }
                }
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (state.busy || state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else HorizontalDivider(Modifier.align(Alignment.BottomCenter))
                }
            }
        },
        bottomBar = {
            if (state.signedIn && state.selectedId != null) {
                Row(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(value = state.draft, onValueChange = viewModel::setDraft,
                        placeholder = { Text("Message") }, maxLines = 4, modifier = Modifier.weight(1f))
                    IconButton(onClick = viewModel::send, enabled = state.draft.isNotBlank() && !state.busy,
                        modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send message") }
                }
            }
        },
        floatingActionButton = {
            if (state.signedIn && state.selectedId == null) FloatingActionButton(onClick = { peer = ""; newChat = true }) {
                Icon(Icons.Default.Add, "New conversation")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    IconButton(onClick = viewModel::dismissError) { Icon(Icons.Default.Close, "Dismiss error") }
                }
            }
            when {
                state.restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !state.signedIn -> SignInForm(state.serverUrl.ifBlank { defaultServer }, state.busy, viewModel::authenticate)
                state.selectedId == null -> {
                    if (state.conversations.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No conversations", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else LazyColumn(Modifier.fillMaxSize()) {
                        items(state.conversations, key = { it.id }) { conversation ->
                            val peers = conversation.members.filter { it != state.accountId }.sorted()
                            ListItem(headlineContent = { Text(if (peers.size == 1) "Account ${peers.first().take(8)}" else "${conversation.members.size} members") },
                                supportingContent = { Text(conversation.id, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { Icon(Icons.Default.Person, null) },
                                modifier = Modifier.clickable(enabled = !state.busy) { viewModel.selectConversation(conversation.id) })
                            HorizontalDivider(Modifier.padding(start = 56.dp))
                        }
                    }
                }
                else -> LazyColumn(state = timeline, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.messages, key = { "message:${it.id}" }) { message ->
                        MessageBubble(message.body, message.senderId == state.accountId, if (message.senderId == state.accountId) "Sent" else null)
                    }
                    items(state.pending, key = { "pending:${it.key}" }) { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (message.state == "FAILED") IconButton(onClick = { viewModel.retry(message.key) }, enabled = !state.busy) { Icon(Icons.Default.Refresh, "Retry message") }
                            MessageBubble(message.body, true, when (message.state) { "FAILED" -> "Failed"; "SENDING" -> "Sending"; else -> "Queued" }, Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
        }
    }

    if (newChat && state.signedIn) AlertDialog(onDismissRequest = { if (!state.busy) newChat = false },
        title = { Text("New conversation") },
        text = { Column { OutlinedTextField(value = peer, onValueChange = { peer = it }, label = { Text("Account ID") }, singleLine = true)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } } },
        confirmButton = { TextButton(onClick = { viewModel.createConversation(peer) }, enabled = peer.isNotBlank() && !state.busy) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Create") } },
        dismissButton = { TextButton(onClick = { newChat = false }, enabled = !state.busy) { Text("Cancel") } })

    if (showAccount && state.signedIn) AlertDialog(onDismissRequest = { showAccount = false }, title = { Text("Account") },
        text = { SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.accountId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(state.serverUrl, style = MaterialTheme.typography.bodySmall)
            state.selectedId?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        } } },
        confirmButton = { TextButton(onClick = { showAccount = false }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { showAccount = false; confirmLogout = true }, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ExitToApp, null); Spacer(Modifier.width(8.dp)); Text("Sign out") } })

    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Sign out?") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.logout() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } })
}

@Composable
private fun SignInForm(server: String, busy: Boolean, authenticate: (String, String, String, Boolean) -> Unit) {
    var address by remember(server) { mutableStateOf(server) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PrimaryTabRow(selectedTabIndex = if (register) 1 else 0) {
                Tab(selected = !register, onClick = { register = false }, text = { Text("Sign in") })
                Tab(selected = register, onClick = { register = true }, text = { Text("Register") })
            }
        }
        item { OutlinedTextField(address, { address = it }, label = { Text("Server") }, placeholder = { Text("https://chat.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
        item { OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)) }
        item { OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPassword) "Hide password" else "Show password") } }) }
        item { Button(onClick = { authenticate(address, email, password, register) }, enabled = !busy && address.isNotBlank() && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(if (register) "Register" else "Sign in") } }
    }
}

@Composable
private fun MessageBubble(body: String, outgoing: Boolean, status: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(color = if (outgoing) Color(0xFFD9FDD3) else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                SelectionContainer { Text(body) }
                status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) }
            }
        }
    }
}
