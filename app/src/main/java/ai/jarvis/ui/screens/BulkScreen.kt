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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.AppMode
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.BanRiskBanner
import ai.jarvis.ui.components.ChipRow
import ai.jarvis.ui.components.CodeBlock
import ai.jarvis.ui.components.EmptyState
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.nav.Routes
import ai.jarvis.ui.theme.LocalJarvisColors
import ai.jarvis.whatsapp.bulk.BulkContent
import ai.jarvis.whatsapp.bulk.BulkOptions
import ai.jarvis.whatsapp.bulk.ParseSummary
import ai.jarvis.whatsapp.bulk.RecipientInputParser
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BulkScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    val progress by container.bulkExecutor.progress.collectAsState()

    val prefill by container.bulkPrefill.collectAsState()
    var type by remember { mutableStateOf("TEXT") }
    var text by remember { mutableStateOf(settings.lastBulkTemplate) }
    LaunchedEffect(prefill) {
        val p = prefill ?: return@LaunchedEffect
        type = p.type
        text = p.text
        container.bulkPrefill.value = null
    }
    var caption by remember { mutableStateOf("") }
    var mediaUrl by remember { mutableStateOf("") }
    var mediaBase64 by remember { mutableStateOf<String?>(null) }
    var mediaName by remember { mutableStateOf("") }
    var rawInput by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<ParseSummary?>(null) }
    var delaySeconds by remember { mutableStateOf(4) }
    var randomize by remember { mutableStateOf(true) }
    var stopOnError by remember { mutableStateOf(false) }
    var dryRun by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var includeGroups by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeBatchId by remember { mutableStateOf<String?>(null) }
    var showPreviewJson by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull().orEmpty()
                }
                if (content.isBlank()) {
                    message = "File padhi nahi ja saki."
                } else {
                    parsed = RecipientInputParser.parseCsv(content)
                    rawInput = content.take(4000)
                    message = "CSV parsed: ${parsed?.valid?.size} valid, ${parsed?.invalid?.size} invalid."
                }
            }
        }
    }

    LaunchedEffect(rawInput) {
        if (rawInput.isNotBlank()) {
            parsed = RecipientInputParser.parsePasted(rawInput)
        }
    }

    val types = listOf("TEXT", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "STICKER", "LOCATION", "CONTACT", "POLL", "TEMPLATE")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Bulk Broadcast", style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Text(
            "CSV/TXT, paste, ya contacts — validation, pacing, resume",
            style = MaterialTheme.typography.bodySmall, color = colors.muted,
        )
        Spacer(Modifier.height(10.dp))

        SectionLabel("STEP 1 — Message type")
        ChipRow(types, type) { type = it }
        if (type !in setOf("TEXT", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "TEMPLATE")) {
            Spacer(Modifier.height(6.dp))
            Text(
                "ℹ ${type} ke liye per-message sequential send hota hai (send-bulk API is type ko support nahi karta) — thoda dheema.",
                style = MaterialTheme.typography.labelSmall, color = colors.warn,
            )
        }

        SectionLabel("STEP 2 — Content")
        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                when (type) {
                    "TEXT" -> OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(), maxLines = 6,
                        placeholder = { Text("Message… {{name}} {{number}} {{date}} variables") },
                    )
                    "IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "STICKER" -> {
                        OutlinedTextField(
                            value = mediaUrl, onValueChange = { mediaUrl = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("Public media URL (server fetch karega)") },
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { if (mediaUrl.isBlank()) message = "Pehle URL daalo ya file pick karo (base64)." else filePicker.launch(arrayOf("*/*")) }) {
                            Text("Ya file pick karo (base64)")
                        }
                        mediaName.takeIf { it.isNotBlank() }?.let {
                            Text("Selected: $it", style = MaterialTheme.typography.labelSmall, color = colors.cyan)
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = caption, onValueChange = { caption = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("Caption (optional, ≤1024)") },
                        )
                        if (type == "DOCUMENT") {
                            Text(
                                "Document ke liye public URL recommended (base64 25 MiB limit tak).",
                                style = MaterialTheme.typography.labelSmall, color = colors.muted,
                            )
                        }
                    }
                    "LOCATION" -> Text(
                        "Location bulk ke liye LAT/LONG picker required hai — Media Send screen se single location test karo. " +
                            "Batch me location send ke liye pehle settings me coordinates set karo (0.0 default).",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                    "CONTACT" -> Text(
                        "Contact card: batch recipient ka apna number/naam use hoga.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                    "POLL" -> Text(
                        "Poll bulk: Media Send screen se ek poll banao, phir batch me same poll options use honge.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                    "TEMPLATE" -> Text(
                        "Template: Templates screen se template choose karo. Variables {{var}} CSV columns se aayenge.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                }
            }
        }

        SectionLabel("STEP 3 — Recipients")
        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = rawInput, onValueChange = { rawInput = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = { Text("Ek line me ek number\n919812345678\n+91 90000 11111, Rahul\nname,number") },
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "*/*")) }) {
                        Text("Upload CSV/TXT")
                    }
                    OutlinedButton(onClick = { rawInput = ""; parsed = null }) { Text("Clear") }
                }
                parsed?.let { p ->
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip("${p.valid.size} valid", Tone.OK)
                        StatusChip("${p.invalid.size} invalid", if (p.invalid.isEmpty()) Tone.NEUTRAL else Tone.ERROR)
                        StatusChip("${p.duplicates} dupes", Tone.NEUTRAL)
                        StatusChip("${p.groups.size} groups", if (p.groups.isEmpty()) Tone.NEUTRAL else Tone.WARN)
                    }
                    if (p.groups.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeGroups, onCheckedChange = { includeGroups = it })
                            Text("Groups include karo (warning: poore group ko ping jayega)", style = MaterialTheme.typography.bodySmall, color = colors.warn)
                        }
                    }
                    p.invalid.take(3).forEach {
                        Text("✗ ${it.raw} — ${it.reason}", style = MaterialTheme.typography.labelSmall, color = colors.danger)
                    }
                }
            }
        }

        SectionLabel("STEP 4 — Options")
        JarvisCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Delay between messages", style = MaterialTheme.typography.bodySmall, color = colors.text, modifier = Modifier.weight(1f))
                    Text("${delaySeconds}s", style = MaterialTheme.typography.labelMedium, color = colors.cyan)
                }
                androidx.compose.material3.Slider(
                    value = delaySeconds.toFloat(), onValueChange = { delaySeconds = it.toInt().coerceIn(1, 60) },
                    valueRange = 1f..60f, steps = 59,
                )
                ToggleRow("Randomize ±2s", randomize) { randomize = it }
                ToggleRow("Stop on first error", stopOnError) { stopOnError = it }
                ToggleRow("Dry-run (DEMO — network nahi)", dryRun) { dryRun = it }
            }
        }

        Spacer(Modifier.height(10.dp))
        BanRiskBanner()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = consent, onCheckedChange = { consent = it })
            Text(
                "Main confirm karta hoon ki ye numbers meri opt-in list hain; unofficial automation se number restrict ho sakta hai.",
                style = MaterialTheme.typography.bodySmall, color = colors.text,
            )
        }

        val recipients = (parsed?.valid ?: emptyList()) + if (includeGroups) (parsed?.groups ?: emptyList()) else emptyList()
        val etaSeconds = recipients.size * (delaySeconds + 1)
        Spacer(Modifier.height(8.dp))
        Text(
            "${recipients.size} recipients · est. ${etaSeconds / 60}m ${etaSeconds % 60}s",
            style = MaterialTheme.typography.labelSmall, color = colors.muted,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showPreviewJson = !showPreviewJson }) { Text("Preview JSON") }
            Button(
                onClick = {
                    if (!consent) { message = "Consent checkbox tick karo."; return@Button }
                    if (recipients.isEmpty()) { message = "Koi valid recipient nahi."; return@Button }
                    if (text.isBlank() && type == "TEXT") { message = "Message text khali hai."; return@Button }
                    if (settings.mode == AppMode.DEMO && !dryRun) {
                        message = "DEMO mode me sirf dry-run chalega — Settings me LIVE karo."
                        return@Button
                    }
                    scope.launch {
                        container.settings.update { it.copy(lastBulkTemplate = text) }
                        val content = BulkContent(
                            type = type, text = text, mediaUrl = mediaUrl.ifBlank { null },
                            mediaBase64 = mediaBase64, caption = caption.ifBlank { null },
                        )
                        val batchId = container.bulkExecutor.prepareBatch(
                            recipients = recipients,
                            content = content,
                            options = BulkOptions(delayMs = delaySeconds * 1000, randomize = randomize, stopOnError = stopOnError, dryRun = dryRun),
                        )
                        activeBatchId = batchId
                        container.bulkExecutor.start(batchId)
                        message = "Batch started: $batchId (${recipients.size} rows)"
                    }
                },
                enabled = consent && recipients.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
            ) { Text("SEND BULK") }
        }

        showPreviewJson?.let {
            Spacer(Modifier.height(8.dp))
            CodeBlock(
                """
                POST /api/sessions/{id}/messages/send-bulk
                {
                  "messages": [ ${recipients.size <= 100 ? recipients.size else 100} items ],   // max 100/request
                  "options": {"delayBetweenMessages": ${delaySeconds * 1000}, "randomizeDelay": $randomize, "stopOnError": $stopOnError}
                }
                type=$type  route=${if (type in setOf("TEXT","IMAGE","VIDEO","AUDIO","DOCUMENT")) "send-bulk" else "sequential single send"}
                """.trimIndent(),
            )
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.cyan)
        }

        if (progress.total > 0) {
            SectionLabel("Progress")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${progress.sent}/${progress.total} sent", style = MaterialTheme.typography.titleMedium, color = colors.text)
                        Spacer(Modifier.weight(1f))
                        StatusChip(progress.state, when (progress.state) {
                            "RUNNING" -> Tone.INFO
                            "DONE" -> Tone.OK
                            "PAUSED", "CANCELLED" -> Tone.WARN
                            else -> Tone.NEUTRAL
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.cyan,
                        trackColor = colors.surfaceElevated,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "failed=${progress.failed} · pending=${progress.pending} · ${progress.currentRecipient}",
                        style = MaterialTheme.typography.labelSmall, color = colors.muted,
                    )
                    progress.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.danger) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (progress.state == "RUNNING") {
                            OutlinedButton(onClick = { container.bulkExecutor.pause() }) { Text("Pause") }
                            TextButton(onClick = { activeBatchId?.let { container.bulkExecutor.cancel(it) } }) { Text("Cancel", color = colors.danger) }
                        } else if (progress.state == "PAUSED") {
                            Button(onClick = { activeBatchId?.let { container.bulkExecutor.resume(it) } }) { Text("Resume") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalJarvisColors.current.text, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}
