package ai.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.ApiResult
import ai.jarvis.core.AppMode
import ai.jarvis.core.AutoReplyMode
import ai.jarvis.data.api.ContactDto
import ai.jarvis.di.AppContainer
import ai.jarvis.domain.policy.CategoryGroup
import ai.jarvis.domain.policy.ReplyCategory
import ai.jarvis.service.AutoReplyService
import ai.jarvis.ui.components.BanRiskBanner
import ai.jarvis.ui.components.CodeBlock
import ai.jarvis.ui.components.EmptyState
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.nav.Routes
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch

@Composable
fun AutoReplyScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    val engine by container.autoReplyEngine.state.collectAsState()
    val allowlist by container.database.autoReplyDao().observeAllowlist().collectAsState(initial = emptyList())
    val logs by container.database.autopilotLogDao().observeRecent(60).collectAsState(initial = emptyList())
    val pendingEsc by container.database.escalationDao().observePending().collectAsState(initial = emptyList())

    var contacts by remember { mutableStateOf<List<ContactDto>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var serverRulesNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        container.autoReplyEngine.refreshState()
        if (settings.mode == AppMode.LIVE && settings.sessionId.isNotBlank()) {
            when (val res = container.api.listRules(settings.sessionId)) {
                is ApiResult.Success -> serverRulesNote = if (res.data.isEmpty()) null
                else "${res.data.size} server automation rule(s) active hain — double reply se bachne ke liye inhe band rakho."
                is ApiResult.Failure -> serverRulesNote = null
            }
            when (val res = container.api.listContacts(settings.sessionId, limit = 500)) {
                is ApiResult.Success -> contacts = res.data
                is ApiResult.Failure -> Unit
            }
        } else {
            contacts = container.demo.contacts()
        }
    }

    fun toggleMode(mode: AutoReplyMode) {
        scope.launch {
            container.autoReplyEngine.setMode(mode)
            if (mode != AutoReplyMode.OFF && settings.mode == AppMode.LIVE) {
                runCatching { AutoReplyService.start(context) }
            } else if (mode == AutoReplyMode.OFF) {
                runCatching { AutoReplyService.stop(context) }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Auto-Reply Engine", style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Text(
            "Greetings/congrats/thanks khud reply · office/paise/legal/OTP kabhi nahi",
            style = MaterialTheme.typography.bodySmall, color = colors.muted,
        )
        Spacer(Modifier.height(10.dp))

        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Master switch", style = MaterialTheme.typography.titleMedium, color = colors.text)
                        Text(
                            when (engine.mode) {
                                "ALL" -> "GLOBAL — sab eligible chats"
                                "ALLOWLIST" -> "ALLOWLIST — sirf ${allowlist.size} chat(s)"
                                else -> "OFF — kuch bhi auto nahi"
                            },
                            style = MaterialTheme.typography.bodySmall, color = colors.muted,
                        )
                    }
                    Switch(
                        checked = engine.mode != "OFF",
                        onCheckedChange = { on -> toggleMode(if (on) AutoReplyMode.ALL else AutoReplyMode.OFF) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("OFF", engine.mode == "OFF") { toggleMode(AutoReplyMode.OFF) }
                    ModeButton("GLOBAL", engine.mode == "ALL") { toggleMode(AutoReplyMode.ALL) }
                    ModeButton("ALLOWLIST", engine.mode == "ALLOWLIST") { toggleMode(AutoReplyMode.ALLOWLIST) }
                }
                if (engine.mode == "ALL") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ GLOBAL mode: strangers ko auto-reply sabse tez ban-risk hai. ALLOWLIST se shuru karo.",
                        style = MaterialTheme.typography.bodySmall, color = colors.warn,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "AUTO OFF/ON: ${engine.autoRepliedToday} replies today · ${engine.escalatedToday} pending approvals · " +
                        if (engine.quietNow) "quiet hours" else "active hours",
                    style = MaterialTheme.typography.labelSmall, color = colors.muted,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { nav.navigate(Routes.APPROVALS) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = colors.warn, contentColor = colors.amoled),
            ) { Text("Approvals (${pendingEsc.size})") }
            OutlinedButton(onClick = { nav.navigate(Routes.LAB) }, modifier = Modifier.weight(1f)) {
                Text("Auto-reply Lab")
            }
        }

        SectionLabel("Allowlist — per contact / group")
        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                if (allowlist.isEmpty()) {
                    Text(
                        "Koi contact allowlist me nahi. ALLOWLIST mode ke liye contact add karo " +
                            "(jaise: 'Pramila ke liye auto reply on').",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                } else {
                    allowlist.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.contactName, style = MaterialTheme.typography.titleSmall, color = colors.text)
                                Text(entry.apiChatId, style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            }
                            Text(
                                "Remove",
                                style = MaterialTheme.typography.labelSmall, color = colors.danger,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { scope.launch { container.autoReplyEngine.removeFromAllowlist(entry.apiChatId) } }
                                    .padding(8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showPicker = true }) { Text("+ Add contact") }
            }
        }

        if (showPicker) {
            val candidates = contacts.take(40)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showPicker = false },
                title = { Text("Allowlist me add karo") },
                text = {
                    Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                        candidates.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            container.autoReplyEngine.addToAllowlist("${c.number}@c.us", c.displayName)
                                            showPicker = false
                                        }
                                    }
                                    .padding(10.dp)
                            ) {
                                Text(c.displayName, style = MaterialTheme.typography.bodyMedium, color = colors.text)
                                Spacer(Modifier.width(6.dp))
                                Text(c.number, style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            }
                        }
                        if (candidates.isEmpty()) {
                            Text("Contacts available nahi — LIVE mode me WhatsApp contacts load honge.", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { showPicker = false }) { Text("Close") } },
            )
        }

        SectionLabel("Category policy")
        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                ReplyCategory.entries.groupBy { it.group }.forEach { (group, cats) ->
                    Text(group.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.cyan)
                    Spacer(Modifier.height(4.dp))
                    cats.forEach { cat ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cat.label, style = MaterialTheme.typography.bodySmall, color = colors.text, modifier = Modifier.weight(1f))
                            StatusChip(
                                when (group) {
                                    CategoryGroup.AUTO -> "AUTO"
                                    CategoryGroup.NEEDS_ENABLE -> "OPT-IN"
                                    CategoryGroup.ALWAYS_ASK -> "ASK"
                                    CategoryGroup.SILENT -> "SILENT"
                                },
                                when (group) {
                                    CategoryGroup.AUTO -> Tone.OK
                                    CategoryGroup.NEEDS_ENABLE -> Tone.INFO
                                    CategoryGroup.ALWAYS_ASK -> Tone.WARN
                                    CategoryGroup.SILENT -> Tone.ERROR
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        SectionLabel("Safety settings")
        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                SettingRow("Quiet hours", "${settings.quietStart}:00 – ${settings.quietEnd}:00") {
                    scope.launch {
                        container.settings.update { it.copy(quietHoursEnabled = !it.quietHoursEnabled) }
                        container.autoReplyEngine.refreshState()
                    }
                }
                Text(
                    if (settings.quietHoursEnabled) "ON — in hours me reply hold rahega" else "OFF",
                    style = MaterialTheme.typography.labelSmall, color = colors.muted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Caps: ${settings.maxPerChatPerDay}/chat/day · ${settings.maxPerHourTotal}/hour total · " +
                        "cooldown 60s · loop-breaker 3-in-90s (6h block)",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Greeting template:",
                    style = MaterialTheme.typography.labelSmall, color = colors.cyan,
                )
                Text(settings.greetingTemplate, style = MaterialTheme.typography.bodySmall, color = colors.text)
            }
        }

        serverRulesNote?.let {
            Spacer(Modifier.height(10.dp))
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    StatusChip("SERVER RULES ACTIVE", Tone.WARN)
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { nav.navigate(Routes.RULES) }) { Text("Server rules manage karo") }
                }
            }
        }

        SectionLabel("Live decision log")
        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                if (logs.isEmpty()) {
                    EmptyState("Koi decision nahi", "Jab message aayega, yahan decision dikhega.")
                } else {
                    logs.take(12).forEach { log ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusChip(
                                    log.decision,
                                    when (log.decision) {
                                        "AUTO_SEND" -> Tone.OK
                                        "ASK_USER" -> Tone.WARN
                                        "SILENCE" -> Tone.ERROR
                                        else -> Tone.NEUTRAL
                                    },
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(log.category, style = MaterialTheme.typography.labelSmall, color = colors.cyan)
                                Spacer(Modifier.weight(1f))
                                Text("${(log.confidence * 100).toInt()}% · ${log.model}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            }
                            Text(
                                log.inboundText?.take(120) ?: "",
                                style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2,
                            )
                            log.draft?.let { Text("→ $it", style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 2) }
                            log.errorText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.danger, maxLines = 2) }
                        }
                    }
                    if (logs.size > 12) Text("+${logs.size - 12} more in DB", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        BanRiskBanner()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalJarvisColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.cyan.copy(alpha = 0.18f) else colors.surfaceElevated)
            .border(1.dp, if (selected) colors.cyan else colors.border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) colors.cyan else colors.text)
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalJarvisColors.current.text, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, color = LocalJarvisColors.current.muted)
    }
}
