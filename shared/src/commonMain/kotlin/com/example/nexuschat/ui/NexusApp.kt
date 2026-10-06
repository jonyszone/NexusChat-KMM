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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
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

private val NexusGreen = Color(0xFF25D366)
private val NexusDarkGreen = Color(0xFF128C7E)
private val NexusText = Color(0xFF111B21)
private val NexusMuted = Color(0xFF667781)

private enum class NexusTab { CHATS, UPDATES, COMMUNITIES, CALLS }

@Composable
fun NexusApp(viewModel: ChatViewModel) {
    var tab by remember { mutableStateOf(NexusTab.CHATS) }
    var detail by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    when {
        profile -> ProfileScreen(onBack = { profile = false })
        detail -> ChatScreen(viewModel = viewModel, onOpenSettings = { profile = true })
        else -> Scaffold(
            containerColor = Color.White,
            topBar = {
                HomeTopBar(
                    tab = tab,
                    menu = menu,
                    onMenu = { menu = !menu },
                    onProfile = { profile = true }
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
                if (tab != NexusTab.CALLS) {
                    Surface(
                        color = NexusDarkGreen,
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 5.dp,
                        modifier = Modifier.size(56.dp)
                    ) { IconButton(onClick = {}) { Icon(Icons.Default.Add, "New", tint = Color.White) } }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    NexusTab.CHATS -> ChatsScreen(onOpenChat = { detail = true })
                    NexusTab.UPDATES -> UpdatesScreen()
                    NexusTab.COMMUNITIES -> CommunitiesScreen()
                    NexusTab.CALLS -> CallsScreen()
                }
                if (menu) {
                    HomeMenu(
                        onDismiss = { menu = false },
                        onSettings = { menu = false; profile = true },
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
private fun ChatsScreen(onOpenChat: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = Color(0xFFF3F1F2), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, "Search", tint = NexusMuted); Spacer(Modifier.width(12.dp)); Text("Ask Nexus AI or Search", color = NexusMuted)
            }
        }
        Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Archive, "Archived", tint = NexusMuted); Spacer(Modifier.width(22.dp)); Text("Archived", fontWeight = FontWeight.Medium) }
        val chats = listOf("Robiul Bro CTL" to "hm", "MD Sir CTL" to "Voice call", "Toufik Vai CTL" to "api/donor-list === field add hobe bloo...", "@iamshafiulislam (You)" to "/Users/cyberdynetechnologyltd/de...", "Tolarbagh Water ATM Notice" to "~ Irfan Hossain joined using a group", "Jahangir mondol" to "Alhamdulillah", "Ma" to "Yesterday")
        LazyColumn { items(chats) { (name, preview) -> ChatRow(name, preview, onOpenChat) } }
    }
}

@Composable
private fun ChatRow(name: String, preview: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFFD9EAD3)), contentAlignment = Alignment.Center) { Text(name.take(1), color = NexusDarkGreen, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold, color = NexusText); Text(preview, color = NexusMuted, maxLines = 1) }
        Text("Yesterday", color = NexusMuted, style = MaterialTheme.typography.labelSmall)
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
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Profile", style = MaterialTheme.typography.headlineSmall) }
        HorizontalDivider(color = Color(0xFFE9EDEF))
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(160.dp).clip(CircleShape).background(Color(0xFFD9EAD3)), contentAlignment = Alignment.Center) { Text("A", style = MaterialTheme.typography.displayLarge, color = NexusDarkGreen) } }
        ProfileItem("Name", "abu Nusayba"); ProfileItem("About", "Set About"); ProfileItem("Username", "iamshafiulislam"); ProfileItem("Phone", "+880 1773-405828"); ProfileItem("Links", "Add links")
    }
}

@Composable
private fun ProfileItem(title: String, value: String) { Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Person, title, tint = NexusMuted); Spacer(Modifier.width(24.dp)); Column { Text(title, color = NexusText); Text(value, color = if (value.startsWith("Set") || value == "Add links") NexusDarkGreen else NexusMuted) } } }

@Composable
private fun HomeMenu(onDismiss: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) { Surface(color = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp, modifier = modifier.padding(top = 8.dp, end = 8.dp).width(220.dp)) { Column { listOf("New group", "Broadcast lists", "Linked devices", "Starred", "Read all").forEach { Text(it, modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(horizontal = 20.dp, vertical = 14.dp), color = NexusText) }; Text("Settings", modifier = Modifier.fillMaxWidth().clickable { onSettings() }.padding(horizontal = 20.dp, vertical = 14.dp)) } } }

private fun NexusTab.icon() = when (this) { NexusTab.CHATS -> Icons.Default.Chat; NexusTab.UPDATES -> Icons.Default.Update; NexusTab.COMMUNITIES -> Icons.Default.Groups; NexusTab.CALLS -> Icons.Default.Call }
private fun NexusTab.label() = when (this) { NexusTab.CHATS -> "Chats"; NexusTab.UPDATES -> "Updates"; NexusTab.COMMUNITIES -> "Communities"; NexusTab.CALLS -> "Calls" }
