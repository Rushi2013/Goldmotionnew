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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.AutoReplyMode
import ai.jarvis.di.AppContainer
import ai.jarvis.domain.policy.AutoReplyPolicy
import ai.jarvis.domain.policy.Classification
import ai.jarvis.domain.policy.PolicyContext
import ai.jarvis.domain.policy.PolicyVerdict
import ai.jarvis.domain.policy.ReplyCategory
import ai.jarvis.domain.policy.RuleClassifier
import ai.jarvis.domain.policy.SafetyLint
import ai.jarvis.ui.components.CodeBlock
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * §7.12 — Auto-reply Lab. Paste an inbound text, see the category, confidence, policy decision,
 * generated draft and the *exact* payload that would be sent — without messaging anyone.
 */
@Composable
fun AutoReplyLabScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var inbound by remember { mutableStateOf("Congratulations! 🎉") }
    var previous by remember { mutableStateOf("Namaste! Kaise ho?") }
    var familyFlag by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(AutoReplyMode.ALLOWLIST) }
    var busy by remember { mutableStateOf(false) }
    var classification by remember { mutableStateOf<Classification?>(null) }
    var verdict by remember { mutableStateOf<PolicyVerdict?>(null) }
    var draft by remember { mutableStateOf<String?>(null) }
    var lint by remember { mutableStateOf<SafetyLint.Verdict?>(null) }
    var payloadJson by remember { mutableStateOf<String?>(null) }

    val samples = listOf(
        "Congratulations! 🎉", "Thank you so much!", "Hi, kaise ho?",
        "Kal ki report bhej do", "Payment kab tak aayega? Refund chahiye",
        "Aapka OTP 482913 hai, verify karo", "Congratulations, aap lucky draw jeet gaye!",
        "Doctor ne dawai badal di hai", "URGENT abhi call karo", "👋",
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Column(Modifier.weight(1f)) {
                Text("Auto-reply Lab", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Text("Kisi ko message nahi jaata — sirf simulation", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            StatusChip("DRY", Tone.INFO)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = inbound, onValueChange = { inbound = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Inbound message") }, maxLines = 3,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = previous, onValueChange = { previous = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Previous message (context)") }, maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = familyFlag, onCheckedChange = { familyFlag = it })
                Text("Contact is family", style = MaterialTheme.typography.bodySmall, color = colors.text)
                Spacer(Modifier.width(12.dp))
                listOf(AutoReplyMode.OFF, AutoReplyMode.ALL, AutoReplyMode.ALLOWLIST).forEach { m ->
                    val sel = mode == m
                    Text(
                        m.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sel) colors.cyan else colors.muted,
                        modifier = Modifier.clickable { mode = m }.padding(6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val c = container.classifier.classify(
                            text = inbound,
                            contactName = "Pramila",
                            previous = listOf("Them: $previous"),
                            familyFlag = familyFlag,
                            hasSavedFact = false,
                        )
                        classification = c
                        val ctx = PolicyContext(
                            mode = mode,
                            chatInAllowlist = mode == AutoReplyMode.ALLOWLIST,
                            userEnabledCategories = emptySet(),
                            inboundIsQuestion = RuleClassifier.isQuestion(inbound),
                        )
                        val v = AutoReplyPolicy.decide(c, ctx)
                        verdict = v
                        var d: String? = null
                        if (v is PolicyVerdict.AutoSend) {
                            d = AutoReplyPolicy.templateFor(c.category, inbound, "Pramila", settings.greetingTemplate)
                            if (d.isBlank()) d = container.classifier.draft(inbound, c.category, listOf(previous))
                            val l = d?.let {
                                SafetyLint.check(it, c.category, c.language, settings.autoReplyLanguage)
                            }
                            lint = l
                            if (l?.safe == false) d = null
                        }
                        draft = d
                        payloadJson = buildString {
                            appendLine("POST /api/sessions/{id}/messages/send-text")
                            appendLine("{")
                            appendLine("  \"chatId\": \"919812345678@c.us\",")
                            appendLine("  \"text\": \"${d?.replace("\"", "'") ?: "<none — escalated>"}\",")
                            appendLine("  \"Idempotency-Key\": \"autoreply|<session>|<messageId>|${inbound.hashCode()}\"")
                            appendLine("}")
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
            ) { Text(if (busy) "Simulating…" else "Simulate inbound") }

            classification?.let { c ->
                Spacer(Modifier.height(12.dp))
                JarvisCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(c.category.name, when (c.category.group) {
                                ai.jarvis.domain.policy.CategoryGroup.AUTO -> Tone.OK
                                ai.jarvis.domain.policy.CategoryGroup.NEEDS_ENABLE -> Tone.INFO
                                ai.jarvis.domain.policy.CategoryGroup.ALWAYS_ASK -> Tone.WARN
                                ai.jarvis.domain.policy.CategoryGroup.SILENT -> Tone.ERROR
                            })
                            Spacer(Modifier.width(6.dp))
                            StatusChip("${(c.confidence * 100).toInt()}% · ${c.source}", Tone.NEUTRAL)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("language=${c.language} · sentiment=${c.sentiment} · wantsAction=${c.wantsAction} · requiresHuman=${c.requiresHuman}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        c.evidence.takeIf { it.isNotBlank() }?.let {
                            Text("evidence: \"$it\"", style = MaterialTheme.typography.labelSmall, color = colors.cyan)
                        }
                    }
                }
            }

            verdict?.let { v ->
                SectionLabel("Policy decision")
                val (label, tone, reason) = when (v) {
                    is PolicyVerdict.AutoSend -> Triple("AUTO_SEND", Tone.OK, "Auto allowed — reply turant jaayega")
                    is PolicyVerdict.AskUser -> Triple("ASK_USER (escalate)", Tone.WARN, v.reason)
                    is PolicyVerdict.Silence -> Triple("SILENCE", Tone.ERROR, v.reason)
                    is PolicyVerdict.Block -> Triple("BLOCKED", Tone.NEUTRAL, v.reason)
                }
                JarvisCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        StatusChip(label, tone)
                        Spacer(Modifier.height(6.dp))
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = colors.text)
                    }
                }
            }

            draft?.let {
                SectionLabel("Draft that would be sent")
                JarvisCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text)
                        lint?.let { l ->
                            Text(
                                if (l.safe) "✅ safety lint pass" else "❌ lint: ${l.reasonText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (l.safe) colors.success else colors.danger,
                            )
                        }
                    }
                }
            }

            payloadJson?.let {
                SectionLabel("Exact request payload")
                CodeBlock(it, maxLines = 12)
            }

            SectionLabel("Sample inboxes")
            samples.forEach { s ->
                Text(
                    "• $s", style = MaterialTheme.typography.bodySmall, color = colors.cyan,
                    modifier = Modifier.fillMaxWidth().clickable { inbound = s }.padding(vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
