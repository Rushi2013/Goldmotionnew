package ai.jarvis.ui.screens

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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.ai.ProviderHealth
import ai.jarvis.core.AppMode
import ai.jarvis.data.LlmConfig
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.BanRiskBanner
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()

    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var sessionId by remember { mutableStateOf(settings.sessionId) }
    var countryCode by remember { mutableStateOf(settings.defaultCountryCode) }
    var showKey by remember { mutableStateOf(false) }
    var liveMode by remember { mutableStateOf(settings.mode == AppMode.LIVE) }

    var llmProvider by remember { mutableStateOf(settings.llm.provider) }
    var llmBase by remember { mutableStateOf(settings.llm.baseUrl) }
    var llmModel by remember { mutableStateOf(settings.llm.model) }
    var llmKey by remember { mutableStateOf(settings.llm.apiKey) }

    var testResult by remember { mutableStateOf<String?>(null) }
    var llmResult by remember { mutableStateOf<ProviderHealth?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun persist() {
        // Sanitise the gateway URL: scheme, trailing slash, duplicated /api, whitespace.
        val normalized = ai.jarvis.core.HostResolver.normalize(baseUrl, isEmulator = isEmulatorDevice())
        if (normalized.error != null) {
            testResult = "❌ ${normalized.error}"
            return
        }
        baseUrl = normalized.url
        container.settings.update {
            it.copy(
                baseUrl = normalized.url,
                apiKey = apiKey.trim(),
                sessionId = sessionId.trim(),
                defaultCountryCode = countryCode.filter { ch -> ch.isDigit() }.ifBlank { "91" },
                mode = if (liveMode) AppMode.LIVE else AppMode.DEMO,
                llm = LlmConfig(provider = llmProvider, baseUrl = llmBase.trim(), model = llmModel.trim(), apiKey = llmKey, temperature = 0.3),
            )
        }
        testResult = buildString {
            append("Saved. Ab 'Test connection' chalao.")
            normalized.warning?.let { append("\n⚠️ ").append(it) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = colors.text)
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("WhatsApp / OpenWA")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Server URL (…/api)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("http://192.168.1.7:2785/api") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = { Text("API key (X-API-Key)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show", color = colors.cyan) }
                        },
                    )
                    ai.jarvis.core.HostResolver.normalize(baseUrl, isEmulator = isEmulatorDevice()).warning?.let { hint ->
                        Text(
                            "⚠️ $hint",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.warn,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sessionId, onValueChange = { sessionId = it },
                        label = { Text("Session id (uuid)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = countryCode, onValueChange = { countryCode = it },
                        label = { Text("Default country code") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("LIVE mode", style = MaterialTheme.typography.bodyMedium, color = colors.text)
                            Text(
                                "Off = DEMO (koi real send nahi)",
                                style = MaterialTheme.typography.labelSmall, color = colors.muted,
                            )
                        }
                        Switch(checked = liveMode, onCheckedChange = { liveMode = it })
                    }
                    Spacer(Modifier.height(8.dp))
                    StatusChip(
                        if (container.settings.isEncrypted) "Key storage: Keystore encrypted" else "Key storage: PLAIN (keystore unavailable)",
                        if (container.settings.isEncrypted) Tone.OK else Tone.WARN,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { persist() },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
                        ) { Text("Save") }
                        OutlinedButton(
                            onClick = {
                                persist()
                                scope.launch {
                                    testing = true
                                    testResult = container.sessionManager.verifyKeyOnly(apiKey.trim(), baseUrl.trim())
                                    testing = false
                                }
                            },
                        ) { Text(if (testing) "Testing…" else "Test connection") }
                    }
                    testResult?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.text)
                    }
                }
            }

            SectionLabel("AI provider (optional)")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("none", "openai-compat", "gemini", "ollama").forEach { p ->
                            val sel = llmProvider == p
                            Text(
                                p,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel) colors.cyan else colors.muted,
                                modifier = Modifier
                                    .then(if (sel) Modifier else Modifier)
                                    .padding(6.dp)
                                    .clickable {
                                        // Switching the chip must also switch the endpoint, otherwise a
                                        // leftover OpenAI URL gets posted with Gemini's key → HTTP 403.
                                        llmProvider = p
                                        when (p) {
                                            "gemini" -> {
                                                if (!llmBase.contains("generativelanguage")) {
                                                    llmBase = "https://generativelanguage.googleapis.com/v1beta"
                                                }
                                                if (llmModel.isBlank() || llmModel.contains("gpt")) llmModel = "gemini-2.0-flash"
                                            }
                                            "openai-compat" -> {
                                                if (!llmBase.contains("openai.com")) llmBase = "https://api.openai.com/v1"
                                                if (llmModel.isBlank()) llmModel = "gpt-4o-mini"
                                            }
                                            "ollama" -> {
                                                if (llmBase.contains("openai.com") || llmBase.contains("googleapis")) {
                                                    llmBase = "http://10.0.2.2:11434/v1"
                                                }
                                                if (llmModel.contains("gpt") || llmModel.contains("gemini")) llmModel = "llama3.1"
                                            }
                                            else -> Unit
                                        }
                                        llmResult = null
                                    },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = llmBase, onValueChange = { llmBase = it },
                        label = { Text("Base URL (OpenAI-compatible)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = llmModel, onValueChange = { llmModel = it },
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = llmKey, onValueChange = { llmKey = it },
                        label = { Text("API key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { persist() }) { Text("Save") }
                        OutlinedButton(onClick = {
                            persist()
                            scope.launch {
                                llmResult = null
                                llmResult = container.aiGateway.verify()
                            }
                        }) { Text("Test LLM") }
                    }
                    llmResult?.let { h ->
                        Spacer(Modifier.height(8.dp))
                        StatusChip(
                            if (h.ok) "AI Connected · ${h.provider}" else "No AI provider (${h.provider})",
                            if (h.ok) Tone.OK else Tone.WARN,
                        )
                        Text(
                            "${h.message} · ${h.latencyMs}ms · model=${h.model}",
                            style = MaterialTheme.typography.labelSmall, color = colors.muted,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Note: AI key app ke encrypted storage me rehti hai. Bina AI ke app 'Rule-based parser' " +
                            "use karta hai — kuch bhi fake 'AI Connected' nahi dikhaya jayega.",
                        style = MaterialTheme.typography.labelSmall, color = colors.muted,
                    )
                }
            }

            SectionLabel("Voice & notifications")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    ToggleLine("TTS replies", settings.ttsEnabled) { on ->
                        container.settings.update { it.copy(ttsEnabled = on) }
                    }
                    ToggleLine("Notify every auto-reply", settings.notifyEveryReply) { on ->
                        container.settings.update { it.copy(notifyEveryReply = on) }
                    }
                    ToggleLine("Realtime socket", settings.realtimeEnabled) { on ->
                        container.settings.update { it.copy(realtimeEnabled = on) }
                    }
                }
            }

            SectionLabel("Data")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch { container.database.autopilotLogDao().clearAll() }
                        }) { Text("Clear decision log") }
                        OutlinedButton(onClick = {
                            scope.launch { container.database.logDao().clearAll() }
                        }) { Text("Clear API log") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        container.settings.wipe()
                        testResult = "Sab data clear ho gaya — app restart karo."
                    }) { Text("Erase all app data", color = colors.danger) }
                }
            }

            SectionLabel("About")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("JARVIS AI v${ai.jarvis.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall, color = colors.text)
                    Text("OpenWA client · 164 API paths", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ye app OpenWA (unofficial WhatsApp gateway) ka native client hai. Meta Cloud API nahi. " +
                            "Number restriction ka risk non-zero hai — dedicated number use karo. " +
                            "Auto-reply sirf tab chalta hai jab app/service chal raha ho. " +
                            "Regulated/high-volume commercial use ke liye official Cloud API chahiye.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    BanRiskBanner()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalJarvisColors.current.text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** `10.0.2.2` only routes to the host inside the emulator — warn the user on a real phone. */
private fun isEmulatorDevice(): Boolean =
    android.os.Build.FINGERPRINT.startsWith("generic") ||
        android.os.Build.FINGERPRINT.contains("emulator", true) ||
        android.os.Build.MODEL.contains("Emulator", true) ||
        android.os.Build.PRODUCT.contains("sdk", true)
