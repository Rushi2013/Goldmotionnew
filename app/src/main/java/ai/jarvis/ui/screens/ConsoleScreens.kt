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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import ai.jarvis.core.Numbers
import ai.jarvis.core.TimeFmt
import ai.jarvis.data.api.AutomationRuleDto
import ai.jarvis.data.api.ChannelDto
import ai.jarvis.data.api.ContactDto
import ai.jarvis.data.api.GroupInfoDto
import ai.jarvis.data.api.GroupSummaryDto
import ai.jarvis.data.api.LabelDto
import ai.jarvis.data.api.SearchHitDto
import ai.jarvis.data.api.TemplateDto
import ai.jarvis.data.api.WebhookDto
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.BanRiskBanner
import ai.jarvis.ui.components.CodeBlock
import ai.jarvis.ui.components.EmptyState
import ai.jarvis.ui.components.ErrorState
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.LoadingState
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
private fun ConsoleHeader(title: String, subtitle: String, nav: NavHostController, trailing: @Composable (() -> Unit)? = null) {
    val colors = LocalJarvisColors.current
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        trailing?.invoke()
    }
}

@Composable
private fun LiveBadge(container: AppContainer) {
    val settings by container.settings.settings.collectAsState()
    StatusChip(
        if (settings.mode == AppMode.DEMO) "DEMO" else "LIVE",
        if (settings.mode == AppMode.DEMO) Tone.WARN else Tone.OK,
    )
}

// ===================================================================== STATUS / STORIES
@Composable
fun StatusScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var text by remember { mutableStateOf("") }
    var bg by remember { mutableStateOf("#0B0F14") }
    var items by remember { mutableStateOf<List<ai.jarvis.data.api.StatusDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            if (settings.mode == AppMode.DEMO) { loading = false; return@launch }
            when (val res = container.api.statusList(settings.sessionId)) {
                is ApiResult.Success -> items = res.data.statuses
                is ApiResult.Failure -> msg = "${res.error.headline}: ${res.error.oneLine}"
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Status / Stories", "Post & manage 24h statuses", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("Compose text status")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), maxLines = 4, placeholder = { Text("Status text") })
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = bg, onValueChange = { bg = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Background hex") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    if (settings.mode == AppMode.DEMO) { msg = "DEMO — status post nahi hua."; return@launch }
                                    msg = when (val r = container.api.postTextStatus(settings.sessionId, text, bg)) {
                                        is ApiResult.Success -> "✅ Status posted"
                                        is ApiResult.Failure -> "❌ ${r.error.oneLine}"
                                    }
                                    load()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
                        ) { Text("Post text") }
                    }
                }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn, modifier = Modifier.padding(top = 8.dp)) }

            SectionLabel("Statuses")
            when {
                loading -> LoadingState()
                items.isEmpty() -> EmptyState("Koi status nahi", "Contact statuses yahan dikhenge, apne statuses delete kar sakte ho.")
                else -> items.forEach { s ->
                    JarvisCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusChip(s.type.uppercase(), Tone.INFO)
                                Spacer(Modifier.width(6.dp))
                                Text(s.timestamp ?: "", style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 1)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    scope.launch {
                                        container.api.deleteStatus(settings.sessionId, s.id)
                                        load()
                                    }
                                }) { Text("Delete", color = colors.danger) }
                            }
                            s.caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text) }
                            Text(s.id, style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ===================================================================== GROUPS
@Composable
fun GroupsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var groups by remember { mutableStateOf<List<GroupSummaryDto>>(emptyList()) }
    var detail by remember { mutableStateOf<GroupInfoDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var joinCode by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newParticipants by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            loading = true
            if (settings.mode == AppMode.DEMO) { loading = false; return@launch }
            when (val r = container.api.sessionGroups(settings.sessionId)) {
                is ApiResult.Success -> groups = r.data
                is ApiResult.Failure -> msg = "${r.error.headline}: ${r.error.oneLine}"
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Groups", "22 group routes wired", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
            SectionLabel("Create / join")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("New group name") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = newParticipants, onValueChange = { newParticipants = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Participants (comma separated numbers)") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val parts = newParticipants.split(',').mapNotNull { Numbers.toChatId(it.trim()) }
                                msg = when (val r = container.api.createGroup(settings.sessionId, newName, parts)) {
                                    is ApiResult.Success -> "✅ Group create request accepted (${r.status})"
                                    is ApiResult.Failure -> "❌ ${r.error.oneLine}"
                                }
                                load()
                            }
                        }) { Text("Create") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = joinCode, onValueChange = { joinCode = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Invite code") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            msg = when (val r = container.api.joinGroup(settings.sessionId, joinCode.trim())) {
                                is ApiResult.Success -> "✅ Join request accepted"
                                is ApiResult.Failure -> "❌ ${r.error.oneLine}"
                            }
                            load()
                        }
                    }) { Text("Join by code") }
                }
            }

            SectionLabel("Groups (${groups.size})")
            when {
                loading -> LoadingState()
                groups.isEmpty() -> EmptyState("Koi group nahi", "Groups yahan list honge.")
                else -> groups.forEach { g ->
                    JarvisCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = {
                        scope.launch {
                            when (val r = container.api.groupInfo(settings.sessionId, g.id)) {
                                is ApiResult.Success -> detail = r.data
                                is ApiResult.Failure -> msg = "❌ ${r.error.oneLine}"
                            }
                        }
                    }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(g.name, style = MaterialTheme.typography.titleSmall, color = colors.text)
                            Text(g.id, style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            detail?.let { d ->
                SectionLabel("Group info")
                JarvisCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, color = colors.text)
                        Text("${d.participants.size} participants · ${d.id}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        d.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusChip(if (d.announce == true) "ANNOUNCE" else "OPEN", if (d.announce == true) Tone.WARN else Tone.OK)
                            StatusChip(if (d.locked == true) "LOCKED" else "UNLOCKED", if (d.locked == true) Tone.WARN else Tone.OK)
                            StatusChip("${d.participants.count { it.isAdmin }} admins", Tone.INFO)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = subject, onValueChange = { subject = it }, modifier = Modifier.fillMaxWidth(), label = { Text("New subject") }, singleLine = true)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    msg = resultOf(container.api.setGroupSubject(settings.sessionId, d.id, subject))
                                }
                            }) { Text("Change subject") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    msg = resultOf(container.api.groupInviteCode(settings.sessionId, d.id))
                                }
                            }) { Text("Invite code") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    msg = resultOf(container.api.revokeGroupInviteCode(settings.sessionId, d.id))
                                }
                            }) { Text("Revoke") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { scope.launch { msg = resultOf(container.api.leaveGroup(settings.sessionId, d.id)); load() } }) {
                                Text("Leave group", color = colors.danger)
                            }
                            TextButton(onClick = { detail = null }) { Text("Close") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun resultOf(res: ApiResult<*>, okLabel: String = "✅ Done"): String = when (res) {
    is ApiResult.Success -> okLabel
    is ApiResult.Failure -> "❌ ${res.error.headline}: ${res.error.oneLine}"
}

// ===================================================================== CHANNELS
@Composable
fun ChannelsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var channels by remember { mutableStateOf<List<ChannelDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var invite by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            loading = true
            when (val r = container.api.listChannels(settings.sessionId)) {
                is ApiResult.Success -> channels = r.data
                is ApiResult.Failure -> msg = "${r.error.headline}: ${r.error.oneLine}"
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (settings.mode == AppMode.LIVE) load() else loading = false }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Channels / Newsletters", "10 channel routes", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(value = invite, onValueChange = { invite = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Invite code") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch { msg = resultOf(container.api.subscribeChannel(settings.sessionId, invite.trim())); load() }
                        }) { Text("Subscribe") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("New channel name") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        scope.launch { msg = resultOf(container.api.createChannel(settings.sessionId, newName)); load() }
                    }) { Text("Create channel") }
                }
            }
            SectionLabel("Subscribed (${channels.size})")
            when {
                loading -> LoadingState()
                channels.isEmpty() -> EmptyState("Koi channel nahi", "Channel subscribe karo ya naya banao.")
                else -> channels.forEach { c ->
                    JarvisCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { nav.navigate(ai.jarvis.ui.nav.Routes.channel(c.id)) }) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name, style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f))
                                if (c.verified == true) StatusChip("VERIFIED", Tone.OK)
                            }
                            Text("${c.subscriberCount ?: 0} subscribers · ${c.id}", style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ChannelDetailScreen(container: AppContainer, channelId: String, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val settings by container.settings.settings.collectAsState()
    var messages by remember { mutableStateOf<List<SearchHitDto>>(emptyList()) }
    var rawJson by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(channelId) {
        loading = true
        when (val r = container.api.channelMessages(settings.sessionId, channelId)) {
            is ApiResult.Success -> {
                rawJson = r.data.toString()
                messages = emptyList()
            }
            is ApiResult.Failure -> rawJson = "${r.error.oneLine}"
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Channel", channelId, nav)
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (loading) LoadingState("Channel messages…")
            rawJson?.let { CodeBlock(it, maxLines = 30) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ===================================================================== LABELS
@Composable
fun LabelsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var labels by remember { mutableStateOf<List<LabelDto>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#00E5FF") }
    var msg by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            when (val r = container.api.listLabels(settings.sessionId)) {
                is ApiResult.Success -> labels = r.data
                is ApiResult.Failure -> msg = "${r.error.headline}: ${r.error.oneLine}"
            }
        }
    }
    LaunchedEffect(Unit) { if (settings.mode == AppMode.LIVE) load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Labels", "WhatsApp Business labels", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Label name") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = color, onValueChange = { color = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Hex color") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            msg = resultOf(container.api.upsertLabel(settings.sessionId, "label_${System.currentTimeMillis()}", name, color))
                            load()
                        }
                    }) { Text("Create label") }
                }
            }
            SectionLabel("Labels (${labels.size})")
            if (labels.isEmpty()) EmptyState("Koi label nahi", "Labels WhatsApp Business accounts par kaam karte hain.")
            labels.forEach { l ->
                JarvisCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(runCatching { Color(android.graphics.Color.parseColor(l.hexColor)) }.getOrDefault(colors.cyan)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.name, style = MaterialTheme.typography.titleSmall, color = colors.text)
                            Text(l.id, style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        }
                        TextButton(onClick = { scope.launch { resultOf(container.api.deleteLabel(settings.sessionId, l.id)); load() } }) {
                            Text("Delete", color = colors.danger)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ===================================================================== TEMPLATES
@Composable
fun TemplatesScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var templates by remember { mutableStateOf<List<TemplateDto>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("Hi {{name}}, ") }
    var msg by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            when (val r = container.api.listTemplates(settings.sessionId)) {
                is ApiResult.Success -> templates = r.data
                is ApiResult.Failure -> msg = "${r.error.headline}: ${r.error.oneLine}"
            }
        }
    }
    LaunchedEffect(Unit) { if (settings.mode == AppMode.LIVE) load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Templates", "{{var}} placeholders · send-template", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Template name") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Body") }, maxLines = 6)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Detected vars: " + Regex("""\{\{(\w+)\}\}""").findAll(body).map { it.groupValues[1] }.distinct().joinToString(", "),
                        style = MaterialTheme.typography.labelSmall, color = colors.cyan,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    msg = resultOf(container.api.createTemplate(settings.sessionId, name, body))
                                    load()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
                        ) { Text("Save template") }
                        OutlinedButton(onClick = {
                            preview = body.replace(Regex("""\{\{(\w+)\}\}""")) { "<${it.groupValues[1]}>" }
                        }) { Text("Preview") }
                    }
                    if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodySmall, color = colors.text)
                }
            }
            SectionLabel("Saved (${templates.size})")
            if (templates.isEmpty()) EmptyState("Koi template nahi", "Template banao, phir kisi bhi chat me send karo.")
            templates.forEach { t ->
                JarvisCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch {
                                    msg = resultOf(container.api.deleteTemplate(settings.sessionId, t.id))
                                    load()
                                }
                            }) { Text("Delete", color = colors.danger) }
                        }
                        Text(t.body, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 3)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ===================================================================== CATALOG
@Composable
fun CatalogScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var catalogJson by remember { mutableStateOf<String?>(null) }
    var productsJson by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            when (val c = container.api.catalog(settings.sessionId)) {
                is ApiResult.Success -> catalogJson = c.data.toString()
                is ApiResult.Failure -> msg = if (c.error.isUnsupported) "Ye feature is engine par available nahi hai (${c.error.status})." else "${c.error.headline}: ${c.error.oneLine}"
            }
            when (val p = container.api.products(settings.sessionId)) {
                is ApiResult.Success -> productsJson = p.data.toString()
                is ApiResult.Failure -> if (msg == null) msg = p.error.oneLine
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (settings.mode == AppMode.LIVE) load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Catalog", "Business products (engine dependent)", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
            if (loading) LoadingState()
            Text(
                "Catalog whatsapp-web.js par 501 deta hai — us case me app honestly 'not supported' dikhata hai, " +
                    "feature hide nahi karta.",
                style = MaterialTheme.typography.bodySmall, color = colors.muted,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { load() }) { Text("Load catalog") }
            catalogJson?.let { SectionLabel("Catalog"); CodeBlock(it, maxLines = 20) }
            productsJson?.let { SectionLabel("Products"); CodeBlock(it, maxLines = 30) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ===================================================================== CONTACTS
@Composable
fun ContactsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var contacts by remember { mutableStateOf<List<ContactDto>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        if (settings.mode == AppMode.DEMO) {
            contacts = container.demo.contacts()
        } else {
            when (val r = container.api.listContacts(settings.sessionId, limit = 1000)) {
                is ApiResult.Success -> contacts = r.data
                is ApiResult.Failure -> msg = "${r.error.headline}: ${r.error.oneLine}"
            }
        }
        loading = false
    }

    val filtered = contacts.filter {
        query.isBlank() || it.displayName.contains(query, true) || it.number.contains(query.filter { ch -> ch.isDigit() })
    }

    Column(Modifier.fillMaxSize()) {
        ConsoleHeader("Contacts", "${contacts.size} WhatsApp contacts", nav) { LiveBadge(container) }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search name / number") }, singleLine = true,
        )
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (loading) LoadingState()
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(colors.cyan.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Text(c.displayName.take(1).uppercase(), color = colors.cyan)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.displayName, style = MaterialTheme.typography.titleSmall, color = colors.text)
                        Text(c.number, style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    }
                    if (c.isBlocked) StatusChip("BLOCKED", Tone.ERROR)
                    TextButton(onClick = {
                        scope.launch {
                            msg = if (c.isBlocked) resultOf(container.api.unblockContact(settings.sessionId, c.id))
                            else resultOf(container.api.blockContact(settings.sessionId, c.id))
                        }
                    }) { Text(if (c.isBlocked) "Unblock" else "Block", color = colors.warn) }
                }
            }
        }
    }
}

// ===================================================================== NUMBER CHECKER
@Composable
fun NumberCheckerScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var input by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Triple<String, Boolean?, String>>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Number Checker", "GET /contacts/check/{number}", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                placeholder = { Text("Ek line me ek number") },
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        checking = true
                        results = emptyList()
                        val numbers = input.split('\n', ',', ';').map { it.trim() }.filter { it.isNotBlank() }
                        val out = mutableListOf<Triple<String, Boolean?, String>>()
                        for (n in numbers.take(50)) {
                            val chatId = Numbers.toChatId(n, settings.defaultCountryCode)
                            if (chatId == null) {
                                out += Triple(n, false, "Invalid format")
                                continue
                            }
                            if (settings.mode == AppMode.DEMO) {
                                out += Triple(n, null, "DEMO — check nahi kiya")
                                continue
                            }
                            when (val r = container.api.checkNumber(settings.sessionId, chatId.substringBefore('@'))) {
                                is ApiResult.Success -> out += Triple(n, r.data.exists, if (r.data.exists) "WhatsApp par hai (${r.data.whatsappId})" else "WhatsApp par nahi")
                                is ApiResult.Failure -> out += Triple(n, null, "${r.error.headline}: ${r.error.oneLine}")
                            }
                        }
                        results = out
                        checking = false
                    }
                },
                enabled = !checking,
                colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
            ) { Text(if (checking) "Checking…" else "Check numbers") }
            Spacer(Modifier.height(8.dp))
            Text(
                "Note: /contacts/check ke liye session 'ready' hona chahiye (warna 409). " +
                    "Har number WhatsApp par hona zaroori nahi ki wo opt-in bhi ho.",
                style = MaterialTheme.typography.labelSmall, color = colors.muted,
            )
            Spacer(Modifier.height(10.dp))
            results.forEach { (number, exists, note) ->
                JarvisCard(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(number, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.weight(1f))
                        StatusChip(
                            when (exists) { true -> "ON WA"; false -> "NOT ON WA"; null -> "UNKNOWN" },
                            when (exists) { true -> Tone.OK; false -> Tone.ERROR; null -> Tone.NEUTRAL },
                        )
                    }
                }
                Text(note, style = MaterialTheme.typography.labelSmall, color = colors.muted, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ===================================================================== SEARCH
@Composable
fun SearchScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHitDto>>(emptyList()) }
    var raw by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Global search", "GET /api/search", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search text (min 3 chars)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        searching = true
                        msg = null
                        val json = ai.jarvis.data.api.HttpCore({ settings.baseUrl }, { settings.apiKey }).json
                        when (val r = container.api.search(query)) {
                            is ApiResult.Success -> {
                                raw = r.data.toString()
                                runCatching {
                                    val obj = r.data as kotlinx.serialization.json.JsonObject
                                    val hitsEl = obj["hits"] as? kotlinx.serialization.json.JsonArray
                                    hits = hitsEl?.mapNotNull { el ->
                                        runCatching { json.decodeFromJsonElement(SearchHitDto.serializer(), el) }.getOrNull()
                                    } ?: emptyList()
                                }
                            }
                            is ApiResult.Failure -> msg = "${r.error.headline}: ${r.error.oneLine}"
                        }
                        searching = false
                    }
                },
                enabled = !searching && query.length >= 3,
                colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
            ) { Text(if (searching) "Searching…" else "Search") }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
            Spacer(Modifier.height(10.dp))
            hits.forEach { h ->
                JarvisCard(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(h.snippet ?: h.body ?: "", style = MaterialTheme.typography.bodyMedium, color = colors.text, maxLines = 3)
                        Text("${h.chatId} · ${h.timestamp?.let { TimeFmt.dateTime(if (it < 10_000_000_000L) it * 1000 else it) } ?: ""}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    }
                }
            }
            raw?.takeIf { hits.isEmpty() }?.let { CodeBlock(it, maxLines = 20) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ===================================================================== WEBHOOKS
@Composable
fun WebhooksScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var hooks by remember { mutableStateOf<List<WebhookDto>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var selectedEvents by remember { mutableStateOf(setOf("message.received")) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showEvents by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            when (val r = container.api.listWebhooks(settings.sessionId)) {
                is ApiResult.Success -> hooks = r.data
                is ApiResult.Failure -> msg = "${r.error.headline}: ${r.error.oneLine}"
            }
        }
    }
    LaunchedEffect(Unit) { if (settings.mode == AppMode.LIVE) load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Webhooks", "9 routes · 27 events · HMAC signed", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Endpoint URL") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = secret, onValueChange = { secret = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Secret (write-only, optional)") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    Text("Events: ${selectedEvents.size} selected", style = MaterialTheme.typography.labelSmall, color = colors.cyan)
                    TextButton(onClick = { showEvents = !showEvents }) { Text(if (showEvents) "Hide events" else "Choose events") }
                    if (showEvents) {
                        ai.jarvis.data.api.WebhookEvents.ALL.forEach { ev ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(
                                    checked = selectedEvents.contains(ev),
                                    onCheckedChange = { on ->
                                        selectedEvents = if (on) selectedEvents + ev else selectedEvents - ev
                                    },
                                )
                                Text(ev, style = MaterialTheme.typography.bodySmall, color = colors.text)
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            msg = resultOf(container.api.createWebhook(
                                settings.sessionId, url, selectedEvents.toList(), secret.ifBlank { null },
                            ))
                            load()
                        }
                    }) { Text("Create webhook") }
                }
            }
            SectionLabel("Webhooks (${hooks.size})")
            if (hooks.isEmpty()) EmptyState("Koi webhook nahi", "Webhook banao to receive realtime events server-side.")
            hooks.forEach { h ->
                JarvisCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(h.url, style = MaterialTheme.typography.bodyMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${h.events.size} events · active=${h.active} · retries=${h.retryCount}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { scope.launch { msg = resultOf(container.api.testWebhook(settings.sessionId, h.id), "✅ Test payload sent") } }) { Text("Test") }
                            TextButton(onClick = { scope.launch { msg = resultOf(container.api.deleteWebhook(settings.sessionId, h.id)); load() } }) { Text("Delete", color = colors.danger) }
                        }
                    }
                }
            }
            SectionLabel("Delivery failures")
            OutlinedButton(onClick = {
                scope.launch {
                    when (val r = container.api.deliveryFailures()) {
                        is ApiResult.Success -> msg = r.data.toString().take(600)
                        is ApiResult.Failure -> msg = "${r.error.headline}: ${r.error.oneLine}"
                    }
                }
            }) { Text("Load failures (admin)") }
            msg?.let { Spacer(Modifier.height(8.dp)); CodeBlock(it, maxLines = 16) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ===================================================================== SERVER AUTOMATION RULES
@Composable
fun RulesScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var rules by remember { mutableStateOf<List<AutomationRuleDto>>(emptyList()) }
    var name by remember { mutableStateOf("jarvis-greeting") }
    var reply by remember { mutableStateOf("Namaste! Main JARVIS hoon.") }
    var keywords by remember { mutableStateOf("hi, hello, namaste") }
    var cooldown by remember { mutableStateOf("120") }
    var msg by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            when (val r = container.api.listRules(settings.sessionId)) {
                is ApiResult.Success -> rules = r.data
                is ApiResult.Failure -> msg = "${r.error.headline}: ${r.error.oneLine}"
            }
        }
    }
    LaunchedEffect(Unit) { if (settings.mode == AppMode.LIVE) load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Server automation rules", "Phone off hone par bhi deterministic replies", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    StatusChip("AI nahi — sirf fixed text", Tone.WARN)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Server rules sirf keyword-based fixed replies ke liye hain. App ka AI engine " +
                            "context/classify/escalation karta hai. Dono ON hone par double-reply ho sakta hai — " +
                            "app warning dikhata hai.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
            SectionLabel("Create rule")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Rule name") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = keywords, onValueChange = { keywords = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Keywords (comma)") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = reply, onValueChange = { reply = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Reply text") }, maxLines = 3)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = cooldown, onValueChange = { cooldown = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Cooldown (seconds)") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            val conditions = buildJsonObject {
                                put("conditions", kotlinx.serialization.json.buildJsonArray {
                                    add(buildJsonObject {
                                        put("field", "body")
                                        put("operator", "contains")
                                        put("value", kotlinx.serialization.json.buildJsonArray {
                                            keywords.split(',').forEach { k -> add(kotlinx.serialization.json.JsonPrimitive(k.trim())) }
                                        })
                                        put("caseSensitive", false)
                                    })
                                })
                            }
                            msg = resultOf(container.api.createRule(
                                settings.sessionId, name, reply, conditions,
                                cooldown.toIntOrNull() ?: 120,
                            ))
                            load()
                        }
                    }) { Text("Create rule") }
                }
            }
            SectionLabel("Rules (${rules.size})")
            if (rules.isEmpty()) EmptyState("Koi server rule nahi", "Rules banao to phone band hone par bhi fixed reply jaaye.")
            rules.forEach { r ->
                JarvisCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f))
                            StatusChip(if (r.enabled) "ON" else "OFF", if (r.enabled) Tone.OK else Tone.NEUTRAL)
                        }
                        Text(r.replyText, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2)
                        Text("cooldown=${r.cooldownSeconds}s · id=${r.id.take(8)}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    msg = resultOf(container.api.updateRule(
                                        settings.sessionId, r.id,
                                        buildJsonObject { put("enabled", !r.enabled) },
                                    ))
                                    load()
                                }
                            }) { Text(if (r.enabled) "Disable" else "Enable") }
                            TextButton(onClick = {
                                scope.launch { msg = resultOf(container.api.deleteRule(settings.sessionId, r.id)); load() }
                            }) { Text("Delete", color = colors.danger) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ===================================================================== STATS
@Composable
fun StatsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var overview by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<String?>(null) }
    var sessionStats by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            msg = null
            when (val r = container.api.statsOverview()) {
                is ApiResult.Success -> overview = r.data.toString()
                is ApiResult.Failure -> msg = if (r.error.isScopeProblem) "Operator key ko /stats/overview par 403 milta hai (admin-only). Session stats try karo." else "${r.error.headline}: ${r.error.oneLine}"
            }
            when (val r = container.api.sessionStats(settings.sessionId)) {
                is ApiResult.Success -> sessionStats = r.data.toString()
                is ApiResult.Failure -> if (msg == null) msg = r.error.oneLine
            }
            when (val r = container.api.messageStats()) {
                is ApiResult.Success -> messages = r.data.toString()
                is ApiResult.Failure -> Unit
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (settings.mode == AppMode.LIVE) load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleHeader("Statistics", "Real JSON — koi fake chart nahi", nav) { LiveBadge(container) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn) }
            if (loading) LoadingState()
            OutlinedButton(onClick = { load() }) { Text("Refresh") }
            sessionStats?.let { SectionLabel("Session stats"); CodeBlock(it, maxLines = 24) }
            overview?.let { SectionLabel("Global overview (admin)"); CodeBlock(it, maxLines = 20) }
            messages?.let { SectionLabel("Message stats"); CodeBlock(it, maxLines = 24) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
