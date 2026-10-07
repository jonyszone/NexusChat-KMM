package com.example.nexuschat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nexuschat.presentation.ChatViewModel
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.network.ApiKeyStore
import com.example.nexuschat.domain.repository.ChatModeStore
import com.example.nexuschat.domain.repository.ChatSession

private val NexusGreen = Color(0xFF25D366)
private val NexusDarkGreen = Color(0xFF128C7E)
private val NexusText = Color(0xFF111B21)
private val NexusMuted = Color(0xFF667781)

private enum class NexusTab { CHATS, UPDATES, COMMUNITIES, CALLS }

private sealed interface NexusRoute {
    data object Inbox : NexusRoute
    data class Chat(val sessionId: String) : NexusRoute
    data object Settings : NexusRoute
    data object Profile : NexusRoute
}

@Composable
fun NexusApp(
    viewModel: ChatViewModel,
    apiKeyStore: ApiKeyStore? = null,
    modeStore: ChatModeStore? = null,
    onRegisterBackHandler: ((() -> Boolean) -> Unit) = {}
) {
    val state by viewModel.ui.collectAsState()
    var tab by remember { mutableStateOf(NexusTab.CHATS) }
    var route by remember { mutableStateOf<NexusRoute>(NexusRoute.Inbox) }
    var settingsReturn by remember { mutableStateOf<NexusRoute>(NexusRoute.Inbox) }
    var menu by remember { mutableStateOf(false) }

    SideEffect {
        onRegisterBackHandler {
            when (route) {
                NexusRoute.Inbox -> false
                is NexusRoute.Chat -> { route = NexusRoute.Inbox; true }
                NexusRoute.Settings -> { route = settingsReturn; true }
                NexusRoute.Profile -> { route = NexusRoute.Inbox; true }
            }
        }
    }

    when {
        route is NexusRoute.Settings && apiKeyStore != null -> SettingsScreen(
            keyStore = apiKeyStore,
            mode = state.mode,
            onModeChange = viewModel::setMode,
            onBack = { route = settingsReturn }
        )
        route is NexusRoute.Profile -> ProfileScreen(onBack = { route = NexusRoute.Inbox })
        route is NexusRoute.Chat -> ChatScreen(
            viewModel = viewModel,
            onBack = { route = NexusRoute.Inbox },
            onOpenSettings = { settingsReturn = route; route = NexusRoute.Settings }
        )
        else -> Scaffold(
            containerColor = Color.White,
            topBar = {
                HomeTopBar(
                    tab = tab,
                    menu = menu,
                    onMenu = { menu = !menu },
                    onProfile = { route = NexusRoute.Profile }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NexusTab.values().forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(item.icon(), contentDescription = item.label()) },
                            label = { Text(item.label()) }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tab == NexusTab.CHATS) {
                    Surface(
                        color = NexusDarkGreen,
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 5.dp,
                        modifier = Modifier.size(56.dp)
                    ) {
                        IconButton(onClick = {
                            route = NexusRoute.Chat(viewModel.newSession())
                        }) { Icon(Icons.Default.Add, "New chat", tint = Color.White) }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    NexusTab.CHATS -> ChatsScreen(
                        sessions = state.sessions,
                        mode = state.mode,
                        selectedId = state.selectedSessionId,
                        onOpenChat = { sessionId ->
                            viewModel.selectSession(sessionId)
                            route = NexusRoute.Chat(sessionId)
                        }
                    )
                    NexusTab.UPDATES -> UpdatesScreen()
                    NexusTab.COMMUNITIES -> CommunitiesScreen()
                    NexusTab.CALLS -> CallsScreen()
                }
                if (menu) {
                    HomeMenu(
                        onDismiss = { menu = false },
                        onSettings = { menu = false; settingsReturn = route; route = NexusRoute.Settings },
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(tab: NexusTab, menu: Boolean, onMenu: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (tab == NexusTab.CHATS) "NexusChat" else tab.label(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (tab == NexusTab.CHATS) NexusDarkGreen else NexusText, modifier = Modifier.weight(1f))
        if (tab == NexusTab.CHATS || tab == NexusTab.UPDATES) IconButton(onClick = {}) { Icon(Icons.Default.Search, "Search") }
        if (tab == NexusTab.CHATS) IconButton(onClick = onProfile) { Icon(Icons.Default.Person, "Profile") }
        IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "More") }
    }
}

@Composable
private fun ChatsScreen(
    sessions: List<ChatSession>,
    mode: ChatMode,
    selectedId: String?,
    onOpenChat: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = Color(0xFFF3F1F2), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, "Search", tint = NexusMuted); Spacer(Modifier.width(12.dp)); Text("Ask Nexus AI or Search", color = NexusMuted)
            }
        }
        if (mode == ChatMode.DEMO) {
            Surface(color = Color(0xFFFFF2CC), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Demo mode — local sample data, no provider calls.", color = Color(0xFF8A6D00), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        if (sessions.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No chats yet", fontWeight = FontWeight.SemiBold, color = NexusText)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (mode == ChatMode.DEMO) "Start a demo chat with the + button." else "Add a provider key in Settings, then start a chat with the + button.",
                    color = NexusMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            return@Column
        }
        LazyColumn { items(sessions, key = { it.id }) { session -> ChatRow(session, selectedId == session.id, onOpenChat) } }
    }
}

@Composable
private fun ChatRow(session: ChatSession, selected: Boolean, onOpen: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFFF0F2F5) else Color.Transparent)
            .clickable { onOpen(session.id) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFFD9EAD3)), contentAlignment = Alignment.Center) { Text(session.title.take(1).uppercase(), color = NexusDarkGreen, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(session.title, fontWeight = FontWeight.SemiBold, color = NexusText, maxLines = 1); Text("Model: ${session.modelId}", color = NexusMuted, maxLines = 1) }
        Text(relativeTime(session.updatedAt), color = NexusMuted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val minutes = (ChatMessage.now() - epochMillis) / 60_000L
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        else -> "${minutes / (60 * 24)}d"
    }
}

@Composable
private fun UpdatesScreen() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFFD9EAD3)), contentAlignment = Alignment.Center) { Text("You", color = NexusDarkGreen) }
            Spacer(Modifier.width(14.dp)); Column { Text("Add status", fontWeight = FontWeight.SemiBold); Text("Disappears after 24 hours", color = NexusMuted) }
        }
        Text("Channels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Stay updated on topics that matter to you. Find channels to follow below.", color = NexusMuted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
        Text("Find channels to follow", color = NexusMuted)
        listOf("নির্জন গালিব" to "633K followers", "WhatsApp" to "234M followers", "দৈনিক হাসির আলো" to "173K followers", "কার্টুন রাজ্য" to "67K followers").forEach { (name, followers) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFE8D1B0)), contentAlignment = Alignment.Center) { Text(name.take(1)) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); Text(followers, color = NexusMuted) }; Surface(color = Color(0xFFD9FDD3), shape = RoundedCornerShape(20.dp)) { Text("Follow", color = NexusDarkGreen, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
        }
    }
}

@Composable
private fun CommunitiesScreen() { EmptyTab("Communities", "Bring related groups together in one place.") }

@Composable
private fun CallsScreen() { EmptyTab("Calls", "Make private voice and video calls.") }

@Composable
private fun EmptyTab(title: String, message: String) { Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(Icons.Default.Groups, title, tint = NexusDarkGreen, modifier = Modifier.size(64.dp)); Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(message, color = NexusMuted, modifier = Modifier.padding(top = 8.dp)) } }

@Composable
private fun ProfileScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }; Text("Profile", style = MaterialTheme.typography.headlineSmall) }
        HorizontalDivider(color = Color(0xFFE9EDEF))
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(160.dp).clip(CircleShape).background(Color(0xFFD9EAD3)), contentAlignment = Alignment.Center) { Text("A", style = MaterialTheme.typography.displayLarge, color = NexusDarkGreen) } }
        ProfileItem("Name", "Nexus Chat user"); ProfileItem("About", "Set About"); ProfileItem("Username", "Set username"); ProfileItem("Phone", "Add phone number"); ProfileItem("Links", "Add links")
    }
}

@Composable
private fun ProfileItem(title: String, value: String) { Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Person, title, tint = NexusMuted); Spacer(Modifier.width(24.dp)); Column { Text(title, color = NexusText); Text(value, color = if (value.startsWith("Set") || value == "Add links") NexusDarkGreen else NexusMuted) } } }

@Composable
private fun HomeMenu(onDismiss: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) { Surface(color = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp, modifier = modifier.padding(top = 8.dp, end = 8.dp).width(220.dp)) { Column { listOf("New group", "Broadcast lists", "Linked devices", "Starred", "Read all").forEach { Text(it, modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(horizontal = 20.dp, vertical = 14.dp), color = NexusText) }; Text("Settings", modifier = Modifier.fillMaxWidth().clickable { onSettings() }.padding(horizontal = 20.dp, vertical = 14.dp)) } } }

private fun NexusTab.icon() = when (this) { NexusTab.CHATS -> Icons.AutoMirrored.Filled.Chat; NexusTab.UPDATES -> Icons.Default.Update; NexusTab.COMMUNITIES -> Icons.Default.Groups; NexusTab.CALLS -> Icons.Default.Call }
private fun NexusTab.label() = when (this) { NexusTab.CHATS -> "Chats"; NexusTab.UPDATES -> "Updates"; NexusTab.COMMUNITIES -> "Communities"; NexusTab.CALLS -> "Calls" }
