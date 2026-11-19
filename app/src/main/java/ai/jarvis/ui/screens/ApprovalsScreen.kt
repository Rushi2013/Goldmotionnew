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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.ApiResult
import ai.jarvis.core.AppMode
import ai.jarvis.data.db.EscalationEntity
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.EmptyState
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Composable
fun ApprovalsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    val pending by container.database.escalationDao().observePending().collectAsState(initial = emptyList())
    var showConfirmFor by remember { mutableStateOf<EscalationEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val json = remember { Json { ignoreUnknownKeys = true } }

    suspend fun send(esc: EscalationEntity, text: String, quote: Boolean) {
        if (settings.mode == AppMode.DEMO) {
            container.database.escalationDao().setState(esc.id, "SENT")
            message = "DEMO — reply actually nahi bheja gaya (state SENT mark kiya)."
            showConfirmFor = null
            return
        }
        when (val res = container.autoReplyEngine.sendEscalation(esc.id, text, quote)) {
            is ApiResult.Success -> message = "Gateway ne accept kiya (messageId: ${res.data.take(20)}…)"
            is ApiResult.Failure -> message = "${res.error.headline}: ${res.error.oneLine}"
        }
        showConfirmFor = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Column(Modifier.weight(1f)) {
                Text("Approvals", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Text(
                    "${pending.size} pending · office/paise/legal yahan aate hain",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted,
                )
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warn, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (pending.isEmpty()) {
            EmptyState(
                "Koi approval pending nahi",
                "Jab office/kaam, paise, legal, ya urgent message aayega, wo yahan dikhega — " +
                    "auto-reply kabhi nahi jayega.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(pending, key = { it.id }) { esc ->
                    val suggestions = remember(esc.id) {
                        runCatching {
                            json.decodeFromString(ListSerializer(String.serializer()), esc.suggestedJson)
                        }.getOrDefault(emptyList())
                    }
                    var custom by remember(esc.id) { mutableStateOf("") }
                    JarvisCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusChip(esc.category, Tone.WARN)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    esc.chatName ?: esc.chatId.substringBefore('@'),
                                    style = MaterialTheme.typography.labelMedium, color = colors.muted,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(esc.inboundText ?: "", style = MaterialTheme.typography.bodyMedium, color = colors.text)
                            Spacer(Modifier.height(6.dp))
                            Text("Kyun escalate hua: ${esc.reason}", style = MaterialTheme.typography.labelSmall, color = colors.warn)

                            if (suggestions.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text("Suggested drafts (tap to use):", style = MaterialTheme.typography.labelSmall, color = colors.cyan)
                                suggestions.forEach { s ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.surfaceElevated)
                                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                            .clickable { custom = s }
                                            .padding(10.dp)
                                    ) {
                                        Text(s, style = MaterialTheme.typography.bodySmall, color = colors.text)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = custom,
                                onValueChange = { custom = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Apna reply likho…") },
                                maxLines = 4,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { showConfirmFor = esc },
                                    enabled = custom.isNotBlank(),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
                                ) { Text("Send…") }
                                TextButton(onClick = {
                                    scope.launch { container.autoReplyEngine.dismissEscalation(esc.id) }
                                }) { Text("Dismiss", color = colors.muted) }
                            }
                        }
                    }
                }
            }
        }

        showConfirmFor?.let { esc ->
            var body by remember(esc.id) { mutableStateOf("") }
            var quote by remember(esc.id) { mutableStateOf(true) }
            val suggestions = remember(esc.id) {
                runCatching {
                    json.decodeFromString(ListSerializer(String.serializer()), esc.suggestedJson)
                }.getOrDefault(emptyList())
            }
            AlertDialog(
                onDismissRequest = { showConfirmFor = null },
                title = { Text("Reply bhejo?") },
                text = {
                    Column {
                        Text("To: ${esc.chatName ?: esc.chatId}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        Spacer(Modifier.height(6.dp))
                        Text("Inbound: ${esc.inboundText?.take(200) ?: ""}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = body,
                            onValueChange = { body = it },
                            label = { Text("Reply text") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5,
                        )
                        suggestions.take(2).forEach { s ->
                            TextButton(onClick = { body = s }) { Text("Use: $s", style = MaterialTheme.typography.bodySmall) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = quote, onCheckedChange = { quote = it })
                            Text("Quote original", style = MaterialTheme.typography.bodySmall, color = colors.text)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = body.isNotBlank(),
                        onClick = { scope.launch { send(esc, body, quote) } },
                    ) { Text("Send") }
                },
                dismissButton = { TextButton(onClick = { showConfirmFor = null }) { Text("Cancel") } },
            )
        }
    }
}
