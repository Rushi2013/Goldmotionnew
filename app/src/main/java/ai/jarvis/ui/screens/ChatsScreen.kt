package ai.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.ApiResult
import ai.jarvis.core.AppMode
import ai.jarvis.core.TimeFmt
import ai.jarvis.data.api.ChatSummaryDto
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.EmptyState
import ai.jarvis.ui.components.ErrorState
import ai.jarvis.ui.components.LoadingState
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.nav.Routes
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch

@Composable
fun ChatsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val settings by container.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<ChatSummaryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("All") }
    var pics by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            if (settings.mode == AppMode.DEMO) {
                chats = container.demo.chats()
                loading = false
                return@launch
            }
            val sessionId = settings.sessionId
            if (sessionId.isBlank()) {
                error = "Session id set nahi hai — Sessions screen se session select karo."
                loading = false
                return@launch
            }
            if (sessionId.isBlank()) { loading = false; return@launch }
            // ChatDirectory merges /chats with /contacts so a saved number shows its name
            // instead of the raw JID, and holds the result in RAM only.
            when (val res = container.chats.refresh(force = true)) {
                is ApiResult.Success -> {
                    chats = container.chats.enrichedChats()
                    // Avatars are optional and can be slow: fetch lazily in the background so the
                    // list paints immediately (bounded to what is visible-ish).
                    pics = chats.take(40).associate { it.id to container.chats.fetchProfilePic(it.id) }
                }
                is ApiResult.Failure -> error = "${res.error.headline}: ${res.error.oneLine}"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    val filtered = chats.filter { c ->
        val matchesQuery = query.isBlank() || c.name.contains(query, true) ||
            (c.phone?.contains(query.filter { it.isDigit() }) == true)
        val matchesTab = when (tab) {
            "Unread" -> c.unreadCount > 0
            "Groups" -> c.isGroup
            "Archived" -> c.archived
            else -> true
        }
        matchesQuery && matchesTab
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Chats", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Text(
                    "${chats.size} chats · ${chats.count { it.unreadCount > 0 }} unread",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted,
                )
            }
            StatusChip(
                if (settings.mode == AppMode.DEMO) "DEMO" else "LIVE",
                if (settings.mode == AppMode.DEMO) Tone.WARN else Tone.OK,
            )
            IconButton(onClick = { nav.navigate(Routes.SEARCH) }) {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = colors.cyan)
            }
            IconButton(onClick = { nav.navigate(Routes.STATUS_HUB) }) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = "Status & views",
                    tint = colors.cyan,
                )
            }
        }

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search chats") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Unread", "Groups", "Archived").forEach { t ->
                val sel = tab == t
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) colors.cyan.copy(alpha = 0.16f) else colors.surfaceElevated)
                        .clickable { tab = t }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(t, style = MaterialTheme.typography.labelMedium, color = if (sel) colors.cyan else colors.muted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> LoadingState()
            error != null -> ErrorState("Chats load nahi hue", error!!) { load() }
            filtered.isEmpty() -> EmptyState(
                "Koi chat nahi", "Jab message aayega ya aap bhejoge to chat yahan dikhegi.",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { chat ->
                    ChatRow(
                        chat = chat,
                        picUrl = pics[chat.id]?.let { container.api.mediaUrl(it) },
                        apiKey = settings.apiKey.takeIf { it.isNotBlank() },
                    ) { nav.navigate(Routes.thread(chat.id)) }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummaryDto, picUrl: String? = null, apiKey: String? = null, onClick: () -> Unit) {
    val colors = LocalJarvisColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(
                if (chat.isGroup) colors.blue.copy(alpha = 0.18f) else colors.cyan.copy(alpha = 0.14f)
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (picUrl != null) {
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(context)
                        .data(picUrl)
                        .apply { apiKey?.let { addHeader("X-API-Key", it) } }
                        .crossfade(true)
                        .build(),
                    contentDescription = chat.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Text(
                    chat.name.take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (chat.isGroup) colors.blue else colors.cyan,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.name.ifBlank { chat.id.substringBefore('@') },
                    style = MaterialTheme.typography.titleSmall, color = colors.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                chat.timestamp?.let {
                    Text(
                        TimeFmt.time(if (it < 10_000_000_000L) it * 1000 else it),
                        style = MaterialTheme.typography.labelSmall, color = colors.muted,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (chat.lastMessage ?: "").ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (chat.unreadCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.clip(CircleShape).background(colors.cyan).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("${chat.unreadCount}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF00161C), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (chat.isGroup) StatusChip("GROUP", Tone.INFO)
                if (chat.pinned) StatusChip("PINNED", Tone.NEUTRAL)
                if (chat.muted) StatusChip("MUTED", Tone.NEUTRAL)
                if (chat.archived) StatusChip("ARCHIVED", Tone.NEUTRAL)
            }
        }
    }
}
