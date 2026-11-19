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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.ApiResult
import ai.jarvis.core.AppMode
import ai.jarvis.core.DeliveryState
import ai.jarvis.core.TimeFmt
import ai.jarvis.data.api.MessageDto
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.CodeBlock
import ai.jarvis.ui.components.DemoBanner
import ai.jarvis.ui.components.EmptyState
import ai.jarvis.ui.components.ErrorState
import ai.jarvis.ui.components.LoadingState
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.nav.Routes
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch

@Composable
fun ThreadScreen(container: AppContainer, chatId: String, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val settings by container.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<MessageDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var lastOutcome by remember { mutableStateOf<String?>(null) }
    var showAttach by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<MessageDto?>(null) }
    val listState = rememberLazyListState()

    fun load() {
        scope.launch {
            loading = true
            error = null
            if (settings.mode == AppMode.DEMO) {
                messages = container.demo.messages(chatId)
                loading = false
                return@launch
            }
            when (val res = container.api.messages(settings.sessionId, chatId = chatId, limit = 150, orderBy = "asc")) {
                is ApiResult.Success -> messages = res.data.messages
                is ApiResult.Failure -> error = "${res.error.headline}: ${res.error.oneLine}"
            }
            loading = false
        }
    }

    LaunchedEffect(chatId) { load() }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        scope.launch {
            sending = true
            lastOutcome = null
            if (settings.mode == AppMode.DEMO) {
                messages = messages + container.demo.outgoing(chatId, text)
                lastOutcome = "DEMO — message actually nahi bheja gaya."
                draft = ""
                sending = false
                return@launch
            }
            when (val res = container.api.sendText(settings.sessionId, chatId, text)) {
                is ApiResult.Success -> {
                    messages = messages + MessageDto(
                        id = res.data.messageId,
                        waMessageId = res.data.messageId,
                        chatId = chatId, from = "me", to = chatId,
                        body = text, type = "text", direction = "outgoing",
                        timestamp = res.data.timestamp, status = "sent",
                    )
                    lastOutcome = "Gateway ne accept kiya (messageId: ${res.data.messageId.take(24)}…). Delivery confirm hone par tick update hoga."
                    draft = ""
                }
                is ApiResult.Failure -> {
                    lastOutcome = "${res.error.headline}: ${res.error.oneLine}"
                }
            }
            sending = false
        }
    }

    fun menuAction(msg: MessageDto, action: String) {
        scope.launch {
            if (settings.mode == AppMode.DEMO) {
                lastOutcome = "DEMO — $action simulate nahi kiya gaya."
                return@launch
            }
            val api = container.api
            val sid = settings.sessionId
            val id = msg.apiMessageId
            val res = when (action) {
                "reply" -> api.reply(sid, chatId, id, draft.ifBlank { "👍" })
                "react" -> api.react(sid, chatId, id, "👍")
                "edit" -> api.editMessage(sid, chatId, id, draft.ifBlank { msg.body ?: "" })
                "delete" -> api.deleteMessage(sid, chatId, id, forEveryone = true)
                "star" -> api.starMessage(sid, chatId, id, true)
                "pin" -> api.pinMessage(sid, chatId, id)
                "forward" -> api.forwardMessage(sid, chatId, chatId, id)
                else -> null
            }
            lastOutcome = if (res == null) "Unknown action" else when (res) {
                is ApiResult.Success -> "✅ $action done"
                is ApiResult.Failure -> "❌ $action: ${res.error.oneLine}"
            }
            load()
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (nav.previousBackStackEntry != null) nav.popBackStack() else nav.navigate(Routes.CHATS)
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text) }
            Column(Modifier.weight(1f)) {
                Text(chatId.substringBefore('@'), style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1)
                Text(
                    if (chatId.endsWith("@g.us")) "Group chat" else "Individual chat",
                    style = MaterialTheme.typography.labelSmall, color = colors.muted,
                )
            }
            StatusChip(
                if (settings.mode == AppMode.DEMO) "DEMO" else "LIVE",
                if (settings.mode == AppMode.DEMO) Tone.WARN else Tone.OK,
            )
        }
        if (settings.mode == AppMode.DEMO) {
            Box(Modifier.padding(horizontal = 16.dp)) { DemoBanner() }
        }

        lastOutcome?.let {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.warn)
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                loading -> LoadingState("Messages load ho rahe hain…")
                error != null -> ErrorState("Thread load nahi hua", error!!) { load() }
                messages.isEmpty() -> EmptyState("Koi message nahi", "Pehla message bhejo.")
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(msg, onClick = { menuFor = msg })
                    }
                }
            }
        }

        menuFor?.let { msg ->
            MessageActionsDialog(
                message = msg,
                onDismiss = { menuFor = null },
                onAction = { action ->
                    menuFor = null
                    menuAction(msg, action)
                },
                onCopy = { lastOutcome = "Copy: ${msg.body}" },
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = { showAttach = true }) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach", tint = colors.cyan)
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message likho…", style = MaterialTheme.typography.bodySmall) },
                maxLines = 4,
            )
            IconButton(onClick = { send() }, enabled = !sending) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (sending) colors.muted else colors.cyan,
                )
            }
        }
        if (showAttach) {
            AttachSheet(onDismiss = { showAttach = false }) { kind ->
                showAttach = false
                nav.navigate(Routes.MEDIA_SEND)
            }
        }
    }
}

@Composable
fun MessageBubble(msg: MessageDto, onClick: (() -> Unit)? = null) {
    val colors = LocalJarvisColors.current
    val outgoing = msg.isOutgoing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (outgoing) colors.bubbleOut else colors.bubbleIn)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(10.dp)
                .width(androidx.compose.ui.unit.Dp.Unspecified)
        ) {
            if (msg.isOutgoing && msg.author != null) {
                Text("You", style = MaterialTheme.typography.labelSmall, color = colors.cyan)
            } else if (!outgoing && msg.author != null) {
                Text(msg.author, style = MaterialTheme.typography.labelSmall, color = colors.blue)
            }
            MediaBadge(msg)
            val body = msg.body.orEmpty()
            if (body.isNotBlank()) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TimeFmt.time(msg.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelSmall, color = colors.muted,
                )
                Spacer(Modifier.width(6.dp))
                if (outgoing) {
                    val state = when (msg.status) {
                        "read" -> DeliveryState.READ
                        "delivered" -> DeliveryState.DELIVERED
                        "failed" -> DeliveryState.FAILED
                        else -> DeliveryState.ACCEPTED
                    }
                    Text(state.label, style = MaterialTheme.typography.labelSmall, color = colors.cyan.copy(alpha = 0.8f))
                }
            }
        }
    }
}

@Composable
private fun MediaBadge(msg: MessageDto) {
    val colors = LocalJarvisColors.current
    val label = when (msg.type) {
        "image" -> "🖼 Image"
        "video" -> "🎬 Video"
        "audio", "voice" -> "🎙 Audio"
        "document" -> "📄 ${msg.mediaPath?.substringAfterLast('/') ?: "Document"}"
        "sticker" -> " sticker"
        "location" -> "📍 Location"
        "contact" -> "👤 Contact"
        "poll" -> "📊 Poll"
        "revoked" -> "🚫 Deleted"
        else -> null
    } ?: return
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.cyan.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.cyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
fun MessageActionsDialog(
    message: MessageDto,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message actions") },
        text = {
            Column {
                CodeBlock(message.body?.take(200) ?: "media message", maxLines = 4)
                Spacer(Modifier.height(8.dp))
                listOf(
                    "reply" to "Reply (quote)",
                    "react" to "React 👍",
                    "star" to "Star",
                    "pin" to "Pin 24h",
                    "forward" to "Forward to same chat",
                    "edit" to "Edit (own only)",
                    "delete" to "Delete for everyone",
                ).forEach { (action, label) ->
                    TextButton(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun AttachSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val options = listOf(
        "Image" to "IMAGE", "Video" to "VIDEO", "Audio / Voice" to "AUDIO",
        "Document" to "DOCUMENT", "Sticker" to "STICKER", "Location" to "LOCATION",
        "Contact" to "CONTACT", "Poll" to "POLL", "Template" to "TEMPLATE", "Product" to "PRODUCT",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kya bhejna hai?") },
        text = {
            Column {
                options.forEach { (label, kind) ->
                    TextButton(onClick = { onPick(kind) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
