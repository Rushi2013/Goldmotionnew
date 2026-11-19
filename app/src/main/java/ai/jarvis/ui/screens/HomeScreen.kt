package ai.jarvis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.ApiResult
import ai.jarvis.core.AppMode
import ai.jarvis.core.AutoReplyMode
import ai.jarvis.data.contacts.Candidate
import ai.jarvis.di.AppContainer
import ai.jarvis.domain.intents.Calculator
import ai.jarvis.domain.intents.IntentName
import ai.jarvis.domain.intents.IntentResult
import ai.jarvis.domain.intents.PendingAction
import ai.jarvis.service.AutoReplyService
import ai.jarvis.ui.components.DemoBanner
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.JarvisOrb
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.nav.Routes
import ai.jarvis.ui.theme.LocalJarvisColors
import ai.jarvis.voice.VoiceManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    container: AppContainer,
    nav: NavHostController,
    requestPermission: (String) -> Unit = {},
    hasPermission: (String) -> Boolean = { true },
) {
    val colors = LocalJarvisColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    val connection by container.sessionManager.connection.collectAsState()
    val engine by container.autoReplyEngine.state.collectAsState()
    val pendingEscalations by container.database.escalationDao().observePending().collectAsState(initial = emptyList())

    var input by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<String?>(null) }
    var replyTone by remember { mutableStateOf(Tone.INFO) }
    var listening by remember { mutableStateOf(false) }
    var amplitude by remember { mutableStateOf(0f) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    val voice = remember { VoiceManager(context) }
    DisposableEffect(Unit) { onDispose { voice.shutdown() } }

    LaunchedEffect(Unit) {
        container.autoReplyEngine.refreshState()
        if (settings.mode == AppMode.LIVE) {
            container.sessionManager.testConnection()
            if (settings.realtimeEnabled) container.socket.connect()
        }
    }

    fun say(text: String, tone: Tone = Tone.INFO) {
        reply = text
        replyTone = tone
        if (settings.ttsEnabled) voice.speak(text)
    }

    suspend fun resolveContact(name: String): Candidate? {
        val candidates = if (settings.mode == AppMode.LIVE && settings.sessionId.isNotBlank()) {
            when (val res = container.api.listContacts(settings.sessionId, limit = 500)) {
                is ApiResult.Success -> res.data.map {
                    Candidate("${it.number}@c.us", it.displayName, it.number, "openwa")
                }
                is ApiResult.Failure -> emptyList()
            }
        } else {
            container.demo.contacts().map { Candidate("${it.number}@c.us", it.displayName, it.number, "demo") }
        }
        val resolver = ai.jarvis.data.contacts.RecipientResolver()
        val result = resolver.resolve(name, candidates)
        return result.autoSelected ?: result.best
    }

    /**
     * Executes an intent. Anything that sends to a real person requires an explicit confirm:
     * HIGH risk intents become a PendingAction and the user must tap "Confirm & send".
     */
    suspend fun execute(intent: IntentResult) {
        when (intent.intent) {
            IntentName.AUTOREPLY_ON -> {
                container.autoReplyEngine.setMode(AutoReplyMode.ALL)
                if (settings.mode == AppMode.LIVE) runCatching { AutoReplyService.start(context) }
                say(
                    "Auto reply ON — sabke greetings/congrats ka reply khud jaayega. " +
                        "Office/kaam, paise, legal — pehle aapse poochhunga.",
                    Tone.OK,
                )
            }
            IntentName.AUTOREPLY_OFF -> {
                container.autoReplyEngine.setMode(AutoReplyMode.OFF)
                runCatching { AutoReplyService.stop(context) }
                say("Auto reply OFF kar diya.", Tone.WARN)
            }
            IntentName.AUTOREPLY_FOR_CONTACT_ON -> {
                val name = intent.slots.recipientName ?: return say("Contact ka naam samajh nahi aaya.")
                val match = resolveContact(name) ?: return say("'$name' nahi mila — Contacts screen se check karo.", Tone.WARN)
                container.autoReplyEngine.setMode(AutoReplyMode.ALLOWLIST)
                container.autoReplyEngine.addToAllowlist(match.chatId, match.displayName)
                say("Auto reply sirf ${match.displayName} ke liye ON hai.", Tone.OK)
            }
            IntentName.AUTOREPLY_FOR_CONTACT_OFF -> {
                val name = intent.slots.recipientName
                val match = name?.let { resolveContact(it) }
                if (match != null) {
                    container.autoReplyEngine.removeFromAllowlist(match.chatId)
                    say("${match.displayName} ka auto reply band kar diya.", Tone.WARN)
                } else {
                    say("Contact nahi mila.", Tone.WARN)
                }
            }
            IntentName.AUTOREPLY_STATUS -> {
                val s = container.autoReplyEngine.state.value
                val allowed = container.database.autoReplyDao().allowlist()
                say(
                    "Auto reply ${s.mode} hai. ${allowed.size} contact(s) allowlist me: " +
                        allowed.take(5).joinToString(", ") { it.contactName } + ". Aaj ${s.autoRepliedToday} replies, ${s.escalatedToday} pending approvals.",
                    Tone.INFO,
                )
            }
            IntentName.SEND_BULK -> {
                val text = intent.slots.message ?: return say("Kya bhejna hai wo samajh nahi aaya.")
                container.bulkPrefill.value = AppContainer.BulkPrefill(intent.slots.messageType ?: "TEXT", text)
                say("Bulk composer khol raha hoon — \"$text\" sabko. Confirm karke hi jaayega.", Tone.INFO)
                nav.navigate(Routes.BULK)
            }
            IntentName.GET_TIME -> say("Abhi ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())} baje hain.", Tone.OK)
            IntentName.GET_DATE -> say("Aaj ${SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())} hai.", Tone.OK)
            IntentName.CALCULATE -> {
                val expr = intent.slots.expression.orEmpty()
                val value = Calculator.evaluate(expr)
                say(
                    if (value != null) "$expr = ${if (value == value.toLong().toDouble()) value.toLong().toString() else value}"
                    else "Calculation samajh nahi aayi.",
                    if (value != null) Tone.OK else Tone.WARN,
                )
            }
            IntentName.CREATE_REMINDER -> {
                val at = intent.slots.reminderAtEpochMs
                val what = intent.slots.reminderText ?: "Reminder"
                if (at == null) return say("Kab yaad dilau? Jaise: 'kal 6 baje …'.", Tone.WARN)
                container.database.reminderDao().insert(
                    ai.jarvis.data.db.ReminderEntity(text = what, triggerAtEpochMs = at)
                )
                say("Reminder set: \"$what\" — ${SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(at))}", Tone.OK)
            }
            IntentName.LIST_REMINDERS -> {
                say("Reminders abhi DB me save hote hain (Reminder table). Notifications next update me.", Tone.INFO)
            }
            IntentName.WHATSAPP_SESSION_STATUS -> {
                val c = container.sessionManager.connection.value
                say("Session: ${c.badge} · phone=${c.phone ?: "—"} · engine=${c.engine ?: "—"}", if (c.isLiveReady) Tone.OK else Tone.WARN)
            }
            IntentName.LINK_SESSION -> nav.navigate(Routes.SESSIONS)
            IntentName.LIST_ESCALATIONS -> nav.navigate(Routes.APPROVALS)
            IntentName.APPROVE_ESCALATION -> nav.navigate(Routes.APPROVALS)
            IntentName.READ_UNREAD_MESSAGES -> {
                val sessionId = settings.sessionId
                if (settings.mode == AppMode.DEMO || sessionId.isBlank()) {
                    return say("DEMO mode / session missing: unread list nahi hai.", Tone.WARN)
                }
                val chats = container.api.sessionChats(sessionId)
                if (chats is ApiResult.Success) {
                    val unread = chats.data.filter { it.unreadCount > 0 }
                    say(
                        if (unread.isEmpty()) "Koi unread message nahi."
                        else "${unread.size} chats me ${unread.sumOf { it.unreadCount }} unread: " + unread.take(4).joinToString(", ") { it.name },
                        Tone.OK,
                    )
                } else say("Unread list nahi mili.", Tone.WARN)
            }
            IntentName.OPEN_CHAT -> {
                val name = intent.slots.chatName
                val match = name?.let { resolveContact(it) }
                if (match != null) {
                    say("${match.displayName} ka chat khol raha hoon.", Tone.OK)
                    nav.navigate(Routes.thread(match.chatId))
                } else say("'$name' ka chat nahi mila.", Tone.WARN)
            }
            IntentName.CREATE_POLL, IntentName.SHARE_LOCATION, IntentName.SEND_MEDIA_TO_CHAT -> {
                say("Ye Media Send screen se karte hain — fields pre-fill ho jayengi.", Tone.INFO)
                nav.navigate(Routes.MEDIA_SEND)
            }
            IntentName.SEND_WHATSAPP_MESSAGE -> {
                val name = intent.slots.recipientName?.takeIf { it.isNotBlank() }
                val message = intent.slots.message?.takeIf { it.isNotBlank() }
                if (name == null) return say("Kisko bhejna hai? Naam batao.", Tone.WARN)
                if (message == null) {
                    pending = PendingAction(intent)
                    return say("$name ko kya message bhejna hai? Ek baat likho.", Tone.WARN)
                }
                val match = resolveContact(name)
                if (match == null) {
                    return say("\"$name\" contacts me nahi mila. Exact naam ya number bolo.", Tone.WARN)
                }
                if (match.score < 0.92) {
                    return say("\"$name\" ambiguous hai (${match.displayName}). Poora naam bolo.", Tone.WARN)
                }
                pending = PendingAction(intent.copy(slots = intent.slots.copy(
                    recipientName = match.displayName,
                    recipientPhoneHint = match.chatId,
                )))
                say("\"$message\" → ${match.displayName} (${match.maskedNumber}). Bhej doon? Confirm & send dabao.", Tone.INFO)
            }
            IntentName.CONFIRM_YES -> {
                val p = pending
                if (p == null) return say("Koi pending action nahi hai.", Tone.WARN)
                val chatId = p.intent.slots.recipientPhoneHint
                val msg = p.intent.slots.message
                if (chatId == null || msg == null) return say("Action adhura hai.", Tone.WARN)
                pending = null
                if (settings.mode == AppMode.DEMO) {
                    return say("DEMO — message actually nahi bheja gaya.", Tone.WARN)
                }
                when (val res = container.api.sendText(settings.sessionId, chatId, msg)) {
                    is ApiResult.Success -> say(
                        "Gateway ne accept kiya (messageId: ${res.data.messageId.take(22)}…). Delivery confirm hone par tick update hoga.",
                        Tone.OK,
                    )
                    is ApiResult.Failure -> say("Bheja nahi ja saka: ${res.error.oneLine}", Tone.ERROR)
                }
            }
            IntentName.CONFIRM_NO -> {
                pending = null
                say("Cancel kar diya.", Tone.NEUTRAL)
            }
            IntentName.UNKNOWN -> say(intent.clarifiedQuestion ?: "Samajh nahi aaya. Jaise: \"Pramila ko hi bhej\" ya \"auto reply on\".", Tone.WARN)
            else -> say("Ye command abhi Home se directly support nahi — ${intent.intent.name}.", Tone.NEUTRAL)
        }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        scope.launch {
            val intent = container.intentParser.parseWithLlm(text)
            execute(intent)
            input = ""
        }
    }

    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$greeting, Sir.", style = MaterialTheme.typography.headlineMedium, color = colors.text)
                Text(
                    if (engine.enabled) "JARVIS AI ready hai — auto-reply chal raha hai." else "JARVIS AI ready hai.",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted,
                )
            }
            StatusChip(
                if (settings.mode == AppMode.DEMO) "DEMO" else connection.badge,
                when {
                    settings.mode == AppMode.DEMO -> Tone.WARN
                    connection.isLiveReady -> Tone.OK
                    connection.phase == ai.jarvis.data.api.ConnectionState.Phase.UNREACHABLE -> Tone.ERROR
                    else -> Tone.INFO
                },
                onClick = { nav.navigate(Routes.SESSIONS) },
            )
        }

        Spacer(Modifier.height(10.dp))
        if (settings.mode == AppMode.DEMO) DemoBanner()

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val health = container.aiGateway.lastHealth()
            val aiLabel = health?.provider?.takeIf { it != "none" }
                ?: if (settings.llm.provider == "none") "Rules" else settings.llm.provider
            StatusChip(
                "AI: $aiLabel",
                if (health?.ok == true) Tone.OK else Tone.NEUTRAL,
                onClick = { nav.navigate(Routes.SETTINGS) },
            )
            StatusChip(
                "AUTO: ${engine.mode}" + if (engine.allowCount > 0) " (${engine.allowCount})" else "",
                when (engine.mode) { "ALL" -> Tone.OK; "ALLOWLIST" -> Tone.INFO; else -> Tone.NEUTRAL },
                onClick = { nav.navigate(Routes.AUTOREPLY) },
            )
            StatusChip(
                "Approvals: ${pendingEscalations.size}",
                if (pendingEscalations.isEmpty()) Tone.NEUTRAL else Tone.WARN,
                onClick = { nav.navigate(Routes.APPROVALS) },
            )
        }

        JarvisOrb(
            modifier = Modifier.fillMaxWidth(),
            size = 210.dp,
            listening = listening,
            amplitude = amplitude,
            onClick = { nav.navigate(Routes.SETTINGS) },
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "\"Pramila ko hi bhej\" · \"auto reply on\" · \"kal 6 baje reminder\"",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingIcon = {
                IconButton(onClick = {
                    if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                        requestPermission(android.Manifest.permission.RECORD_AUDIO)
                        return@IconButton
                    }
                    listening = !listening
                    if (listening) {
                        voice.startListening(
                            onPartial = { amplitude = 0.6f },
                            onResult = { text ->
                                listening = false
                                amplitude = 0f
                                input = text
                                submit(text)
                            },
                            onError = { err ->
                                listening = false
                                amplitude = 0f
                                say(err, Tone.WARN)
                            },
                        )
                    } else {
                        voice.stop()
                    }
                }) {
                    Icon(
                        if (listening) Icons.Filled.Mic else Icons.Filled.MicNone,
                        contentDescription = if (listening) "Listening" else "Mic",
                        tint = if (listening) colors.cyan else colors.muted,
                    )
                }
            },
        )

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { submit(input) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
            ) { Text("Ask JARVIS") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val next = if (engine.mode == "OFF") AutoReplyMode.ALL else AutoReplyMode.OFF
                        container.autoReplyEngine.setMode(next)
                        if (next != AutoReplyMode.OFF && settings.mode == AppMode.LIVE) {
                            runCatching { AutoReplyService.start(context) }
                        } else if (next == AutoReplyMode.OFF) {
                            runCatching { AutoReplyService.stop(context) }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (engine.mode == "OFF") "Auto reply ON" else "Auto reply OFF") }
        }

        pending?.let { p ->
            Spacer(Modifier.height(8.dp))
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Confirm?", style = MaterialTheme.typography.labelSmall, color = colors.warn)
                    Text(
                        "\"${p.intent.slots.message ?: "?"}\" → ${p.intent.slots.recipientName ?: "?"}",
                        style = MaterialTheme.typography.bodyMedium, color = colors.text,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { execute(IntentResult(IntentName.CONFIRM_YES)) } },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.success, contentColor = colors.onPrimary),
                        ) { Text("Confirm & send") }
                        OutlinedButton(onClick = { pending = null; say("Cancel kar diya.", Tone.NEUTRAL) }) { Text("Cancel") }
                    }
                }
            }
        }

        reply?.let {
            Spacer(Modifier.height(10.dp))
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("JARVIS", style = MaterialTheme.typography.labelSmall, color = colors.cyan)
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text)
                }
            }
        }

        SectionLabel("Quick actions")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(if (pendingEscalations.isEmpty()) "•" else "${pendingEscalations.size}", "Approvals") { nav.navigate(Routes.APPROVALS) }
            QuickAction("→", "Bulk send") { nav.navigate(Routes.BULK) }
            QuickAction("💬", "Chats") { nav.navigate(Routes.CHATS) }
            QuickAction("QR", "Link session") { nav.navigate(Routes.SESSIONS) }
        }

        SectionLabel("Today")
        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    StatBlock("${engine.autoRepliedToday}", "Auto-replies", colors.success, Modifier.weight(1f))
                    StatBlock("${pendingEscalations.size}", "Escalations", colors.warn, Modifier.weight(1f))
                    StatBlock(connection.badge, "Session", colors.cyan, Modifier.weight(1f))
                }
                if (engine.quietNow) {
                    Spacer(Modifier.height(6.dp))
                    Text("Quiet hours active — auto reply hold par hai.", style = MaterialTheme.typography.bodySmall, color = colors.warn)
                }
            }
        }

        SectionLabel("Honest limits")
        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Auto-reply sirf tab chalta hai jab JARVIS ka foreground service chal raha ho — " +
                        "phone band = koi reply nahi. Office/kaam, paise, legal, OTP par kabhi auto-reply nahi; " +
                        "pehle aapse poochha jayega. Bulk me ban-risk warning + consent mandatory hai.",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date()) +
                        " · session: " + settings.sessionName.ifBlank { "none" } +
                        " · " + (if (settings.mode == AppMode.DEMO) "DEMO" else "LIVE"),
                    style = MaterialTheme.typography.labelSmall, color = colors.muted,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickAction(badge: String, label: String, onClick: () -> Unit) {
    val colors = LocalJarvisColors.current
    JarvisCard(Modifier.weight(1f), onClick = onClick) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(badge, style = MaterialTheme.typography.titleMedium, color = colors.cyan)
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 1)
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalJarvisColors.current.muted)
    }
}
