package ai.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.jarvis.core.AppMode
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.BanRiskBanner
import ai.jarvis.ui.components.DemoBanner
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.JarvisOrb
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var baseUrl by remember { mutableStateOf(container.settings.settings.value.baseUrl) }
    var apiKey by remember { mutableStateOf(container.settings.settings.value.apiKey) }
    var sessionId by remember { mutableStateOf(container.settings.settings.value.sessionId) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val steps = listOf("Welcome", "Server", "WhatsApp", "AI (optional)", "Safety")

    Column(Modifier.fillMaxSize().background(colors.amoled)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i <= step) colors.cyan else colors.surfaceElevated)
                )
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        ) {
            Text(steps[step], style = MaterialTheme.typography.labelSmall, color = colors.muted)
            Spacer(Modifier.height(8.dp))

            when (step) {
                0 -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        JarvisOrb(size = 200.dp)
                    }
                    Text(
                        "JARVIS AI",
                        style = MaterialTheme.typography.headlineLarge,
                        color = colors.text,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "WhatsApp ka poora console + AI assistant + auto-reply engine + bulk sender — ek native app me.\n\n" +
                            "164 OpenWA API paths wired · Hindi/English/Hinglish samajhta hai · office/OTP/paise par kabhi auto-reply nahi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    DemoBanner()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Aap DEMO mode me bhi poora app chala sakte ho — koi real message nahi jaayega. " +
                            "LIVE mode ke liye OpenWA server + operator key chahiye.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                }
                1 -> {
                    Text("OpenWA server joड़ो", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Base URL emulator se 10.0.2.2:2785/api hota hai, real phone se apne PC ka LAN IP.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("Operator API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = sessionId, onValueChange = { sessionId = it }, label = { Text("Session id (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            container.settings.update { it.copy(baseUrl = baseUrl.trimEnd('/'), apiKey = apiKey.trim(), sessionId = sessionId.trim()) }
                            testResult = "Saved — ab test karo."
                        }) { Text("Save") }
                        Button(
                            onClick = {
                                container.settings.update { it.copy(baseUrl = baseUrl.trimEnd('/'), apiKey = apiKey.trim(), sessionId = sessionId.trim(), mode = AppMode.LIVE) }
                                scope.launch {
                                    testing = true
                                    testResult = container.sessionManager.verifyKeyOnly(apiKey.trim(), baseUrl.trimEnd('/'))
                                    testing = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
                        ) { Text(if (testing) "Testing…" else "Test & Save") }
                    }
                    testResult?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.text)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("DEMO mode", Tone.WARN)
                        OutlinedButton(onClick = {
                            container.settings.update { it.copy(mode = AppMode.DEMO) }
                            container.sessionManager.markDemo()
                            testResult = "DEMO mode on — koi real send nahi."
                        }) { Text("Stay in DEMO") }
                    }
                }
                2 -> {
                    Text("WhatsApp link karo", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sessions screen se session start karo, QR scan karo (WhatsApp → Linked devices). " +
                            "QR ~20s me refresh hota hai. Real phone se QR scan karo.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    JarvisCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Instructions", style = MaterialTheme.typography.titleSmall, color = colors.cyan)
                            Text("1. Sessions screen kholo", style = MaterialTheme.typography.bodySmall, color = colors.text)
                            Text("2. Start session → QR dikhega", style = MaterialTheme.typography.bodySmall, color = colors.text)
                            Text("3. Phone se scan karo", style = MaterialTheme.typography.bodySmall, color = colors.text)
                            Text("4. Status READY hone ka wait karo", style = MaterialTheme.typography.bodySmall, color = colors.text)
                        }
                    }
                }
                3 -> {
                    Text("AI provider (optional)", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Bina AI ke app 'Rule-based parser' chalata hai — greetings/thanks keywords se samajhta hai. " +
                            "OpenAI-compatible, Gemini, ya Ollama add karke classification/drafting behtar karo. " +
                            "AI key app ke encrypted storage me rehti hai, APK me kabhi nahi.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    JarvisCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            StatusChip(
                                if (container.settings.settings.value.llm.provider == "none") "No AI — Rules" else "AI configured: ${container.settings.settings.value.llm.provider}",
                                if (container.settings.settings.value.llm.provider == "none") Tone.NEUTRAL else Tone.OK,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Settings → AI provider se add kar sakte ho. Abhi skip karo to bhi app kaam karta hai.",
                                style = MaterialTheme.typography.bodySmall, color = colors.muted,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onDone) { Text("Skip — Rules se chalao") }
                        }
                    }
                }
                4 -> {
                    Text("Safety briefing", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ye app unofficial WhatsApp gateway (OpenWA) ka client hai. Ban-risk real hai.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.warn,
                    )
                    Spacer(Modifier.height(10.dp))
                    BanRiskBanner()
                    Spacer(Modifier.height(10.dp))
                    JarvisCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Hard rules jo app enforce karta hai:", style = MaterialTheme.typography.titleSmall, color = colors.cyan)
                            listOf(
                                "Office/kaam, paisa, legal, medical, OTP, complaint, urgent — kabhi auto-reply NAHI. Pehle aapse poochhega.",
                                "Auto-reply OFF/ALL/ALLOWLIST — default OFF. Allowlist se shuru karo.",
                                "Loop breaker: 3 replies in 90s → 6h block.",
                                "5 min se purane messages ka reply nahi (backlog storm se bachne ke liye).",
                                "Bulk me consent + ban-risk warning mandatory.",
                                "Har auto-draft safety lint se guzarta hai (promise/price/link/OTP block).",
                            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = colors.text) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("Back") }
                }
                Button(
                    onClick = {
                        if (step < steps.lastIndex) step++
                        else {
                            container.settings.update {
                                it.copy(
                                    baseUrl = baseUrl.trimEnd('/'), apiKey = apiKey.trim(), sessionId = sessionId.trim(),
                                    onboardingDone = true,
                                )
                            }
                            onDone()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
                ) { Text(if (step == steps.lastIndex) "Enter JARVIS" else "Continue") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
