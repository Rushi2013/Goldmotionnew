package ai.jarvis.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.ApiResult
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.ChipRow
import ai.jarvis.ui.components.CodeBlock
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.nav.Routes
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch

@Composable
fun DevToolsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val logs by container.database.logDao().observeRecent(200).collectAsState(initial = emptyList())
    val settings by container.settings.settings.collectAsState()

    var method by remember { mutableStateOf("GET") }
    var path by remember { mutableStateOf("/sessions") }
    var body by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var versionCheck by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Column(Modifier.weight(1f)) {
                Text("Dev Tools", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Text("API console · logs · version check", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("API console — koi bhi OpenWA route")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    ChipRow(listOf("GET", "POST", "PUT", "PATCH", "DELETE"), method) { method = it }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = path, onValueChange = { path = it },
                        label = { Text("Path (…/api ke baad)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("/sessions/{id}/chats") },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = body, onValueChange = { body = it },
                        label = { Text("JSON body (optional)") },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    result = null
                                    val res = container.api.raw(
                                        method, path,
                                        body = body.takeIf { it.isNotBlank() },
                                    )
                                    result = when (res) {
                                        is ApiResult.Success -> "HTTP ${res.data.httpStatus}\n\n${res.data.body.take(4000).ifBlank { "(empty body)" }}"
                                        is ApiResult.Failure -> "❌ ${res.error.headline}\n${res.error.message}\n\n${res.error.rawBody.take(2000)}"
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
                        ) { Text(if (busy) "Calling…" else "Call") }
                        OutlinedButton(onClick = { result?.let { clipboard.setText(AnnotatedString(it)) } }) { Text("Copy") }
                    }
                }
            }
            result?.let { CodeBlock(it, maxLines = 40) }

            SectionLabel("Version check")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Expected: OpenWA 0.30.3 (contract docs/openwa-openapi.json). Drift ho to app gracefully degrade karta hai.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            versionCheck = when (val r = container.api.infraUpdateCheck()) {
                                is ApiResult.Success -> r.data.toString()
                                is ApiResult.Failure -> "${r.error.headline}: ${r.error.oneLine}"
                            }
                        }
                    }) { Text("Check server version") }
                    versionCheck?.let { CodeBlock(it, maxLines = 14) }
                }
            }

            SectionLabel("Realtime socket")
            val rt by container.socket.connection.collectAsState()
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    StatusChip(
                        "Socket: $rt",
                        when (rt) {
                            ai.jarvis.data.api.SocketIoManager.RealtimeState.CONNECTED -> Tone.OK
                            ai.jarvis.data.api.SocketIoManager.RealtimeState.CONNECTING -> Tone.INFO
                            ai.jarvis.data.api.SocketIoManager.RealtimeState.ERROR -> Tone.ERROR
                            ai.jarvis.data.api.SocketIoManager.RealtimeState.DISCONNECTED -> Tone.NEUTRAL
                        },
                    )
                    container.socket.lastError()?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = colors.danger)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { container.socket.connect() }) { Text("Connect") }
                        OutlinedButton(onClick = { container.socket.disconnect() }) { Text("Disconnect") }
                        OutlinedButton(onClick = { container.socket.subscribe("*", listOf("*")) }) { Text("Subscribe *") }
                    }
                    Text(
                        "URL: ${settings.baseUrl.substringBeforeLast("/api")}/events · ns=/events · auth={apiKey}",
                        style = MaterialTheme.typography.labelSmall, color = colors.muted,
                    )
                }
            }

            SectionLabel("Tests")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.navigate(Routes.INTENT_TEST) }) { Text("Intent Test") }
            }

            SectionLabel("API call log (redacted)")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    if (logs.isEmpty()) {
                        Text("Koi call log nahi.", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    } else {
                        logs.take(60).forEach { l ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (l.statusCode in 200..299) colors.surfaceElevated else colors.danger.copy(alpha = 0.08f))
                                    .padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(l.method, style = MaterialTheme.typography.labelSmall, color = colors.cyan, modifier = Modifier.width(52.dp))
                                Text(l.path.take(40), style = MaterialTheme.typography.labelSmall, color = colors.text, modifier = Modifier.weight(1f))
                                Text("${l.statusCode}", style = MaterialTheme.typography.labelSmall, color = if (l.statusCode in 200..299) colors.success else colors.danger)
                                Spacer(Modifier.width(6.dp))
                                Text("${l.latencyMs}ms", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun IntentTestScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("Pramila ko hi bhej") }
    var result by remember { mutableStateOf<ai.jarvis.domain.intents.IntentResult?>(null) }
    var llmResult by remember { mutableStateOf<ai.jarvis.domain.intents.IntentResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    val presets = listOf(
        "Pramila ko hi bhej", "send hi to Pramila", "प्रमिला को व्हाट्सएप पर हाय भेजो",
        "auto reply on kar do", "Pramila ke liye auto reply on", "auto reply off",
        "sabko good morning bhej do", "kitne baje hain", "12 * 8 + 4",
        "kal 6 baje mom ko call karne ka reminder", "Rahul ka chat kholo",
        "poll banao: lunch ya dinner", "unread messages padho",
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Column {
                Text("Intent Test", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Text("§10.3 fixtures — rules + LLM", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(), maxLines = 4,
                label = { Text("Utterance") },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            result = container.intentParser.parse(input)
                            llmResult = if (result?.intent == ai.jarvis.domain.intents.IntentName.UNKNOWN)
                                container.intentParser.parseWithLlm(input) else null
                            busy = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
                ) { Text(if (busy) "Parsing…" else "Parse (rules)") }
            }
            result?.let { r ->
                Spacer(Modifier.height(10.dp))
                JarvisCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(r.intent.name, Tone.INFO)
                            Spacer(Modifier.width(6.dp))
                            StatusChip("${(r.confidence * 100).toInt()}%", if (r.confidence >= 0.7) Tone.OK else Tone.WARN)
                            Spacer(Modifier.width(6.dp))
                            StatusChip(r.risk.name, if (r.risk == ai.jarvis.core.RiskLevel.HIGH) Tone.ERROR else Tone.NEUTRAL)
                        }
                        Spacer(Modifier.height(8.dp))
                        CodeBlock(
                            """
                            language=${r.language}  source=${r.source}
                            recipientName=${r.slots.recipientName}
                            message=${r.slots.message}
                            scope=${r.slots.autoReplyScope}
                            messageType=${r.slots.messageType}
                            recipientSet=${r.slots.recipientSet}
                            pollOptions=${r.slots.pollOptions}
                            reminderAt=${r.slots.reminderAtEpochMs}
                            expression=${r.slots.expression}
                            missing=${r.missingSlots}
                            needsConfirm=${r.requiresConfirmation}
                            """.trimIndent(),
                            maxLines = 20,
                        )
                        r.clarifiedQuestion?.let { Text("Ask: $it", style = MaterialTheme.typography.bodySmall, color = colors.warn) }
                        r.explanation.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        }
                    }
                }
            }
            llmResult?.let { r ->
                SectionLabel("LLM refinement")
                CodeBlock("${r.intent.name} · ${(r.confidence * 100).toInt()}% · ${r.slots}", maxLines = 8)
            }

            SectionLabel("Presets (spec §10.3)")
            presets.forEach { p ->
                Text(
                    "• $p",
                    style = MaterialTheme.typography.bodySmall, color = colors.cyan,
                    modifier = Modifier.fillMaxWidth().clickable { input = p }.padding(vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
