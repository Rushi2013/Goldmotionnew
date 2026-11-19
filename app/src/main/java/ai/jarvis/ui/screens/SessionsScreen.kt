package ai.jarvis.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.ApiResult
import ai.jarvis.core.AppMode
import ai.jarvis.data.api.SessionDto
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.CodeBlock
import ai.jarvis.ui.components.EmptyState
import ai.jarvis.ui.components.ErrorState
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.LoadingState
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SessionsScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    val connection by container.sessionManager.connection.collectAsState()
    var sessions by remember { mutableStateOf<List<SessionDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrError by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("jarvis") }
    var message by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            if (settings.mode == AppMode.DEMO) {
                error = "DEMO mode: session list nahi dikhega. Settings me LIVE karo."
                loading = false
                return@launch
            }
            when (val res = container.sessionManager.refreshSessions()) {
                is ApiResult.Success -> sessions = res.data
                is ApiResult.Failure -> error = "${res.error.headline}: ${res.error.oneLine}"
            }
            loading = false
        }
    }

    fun fetchQr() {
        scope.launch {
            qrError = null
            when (val res = container.sessionManager.fetchQr()) {
                is ApiResult.Success -> {
                    val b64 = res.data.substringAfter(",", res.data)
                    qrBitmap = runCatching {
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                    if (qrBitmap == null) qrError = "QR decode nahi hua"
                }
                is ApiResult.Failure -> qrError = "${res.error.headline}: ${res.error.oneLine}"
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    // QR refresh loop, only while QR phase is visible
    LaunchedEffect(connection.sessionStatus) {
        if (connection.sessionStatus in setOf("qr_ready", "authenticating")) {
            while (true) {
                fetchQr()
                delay(20_000)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Column(Modifier.weight(1f)) {
                Text("Session / QR", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Text("WhatsApp link karo", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            StatusChip(connection.badge, when {
                connection.isLiveReady -> Tone.OK
                connection.phase == ai.jarvis.data.api.ConnectionState.Phase.UNREACHABLE -> Tone.ERROR
                else -> Tone.WARN
            })
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.cyan) }
            error?.let { ErrorState("Sessions load nahi hue", it) { load() } }

            SectionLabel("Selected session")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        settings.sessionName.ifBlank { "no session selected" },
                        style = MaterialTheme.typography.titleMedium, color = colors.text,
                    )
                    Text(settings.sessionId.ifBlank { "Settings me session id daalo" }, style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "status=${connection.sessionStatus.ifBlank { "unknown" }} · phone=${connection.phone ?: "-"} · engine=${connection.engine ?: "-"}",
                        style = MaterialTheme.typography.labelSmall, color = colors.muted,
                    )
                    connection.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.danger) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    starting = true
                                    message = "Session start ho raha hai (10-60s lag sakta hai)…"
                                    val res = container.sessionManager.startSession { phase -> message = "phase: $phase" }
                                    starting = false
                                    message = when (res) {
                                        is ApiResult.Success -> "phase: ${res.data.status}"
                                        is ApiResult.Failure -> "❌ ${res.error.oneLine}"
                                    }
                                    load()
                                }
                            },
                            enabled = !starting && settings.mode == AppMode.LIVE,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
                        ) { Text(if (starting) "Starting…" else "Start session") }
                        OutlinedButton(onClick = { fetchQr() }, enabled = settings.mode == AppMode.LIVE) { Text("Get QR") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                when (val r = container.api.stopSession(settings.sessionId)) {
                                    is ApiResult.Success -> message = "Session stopped"
                                    is ApiResult.Failure -> message = "❌ ${r.error.oneLine}"
                                }
                                load()
                            }
                        }, enabled = settings.mode == AppMode.LIVE) { Text("Stop") }
                    }
                }
            }

            SectionLabel("QR")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "WhatsApp QR",
                            modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(8.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "WhatsApp → Linked devices → Link a device. QR ~20s me refresh hota hai.",
                            style = MaterialTheme.typography.labelSmall, color = colors.muted,
                        )
                    } else {
                        EmptyState(
                            "QR abhi nahi",
                            qrError ?: "Session start karo, phir QR fetch karo.",
                            action = { TextButton(onClick = { fetchQr() }) { Text("Retry") } },
                        )
                    }
                }
            }

            SectionLabel("All sessions")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    if (loading) LoadingState("Sessions load ho rahe hain…")
                    else if (sessions.isEmpty()) Text("Koi session nahi.", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    else sessions.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    container.settings.update { it.copy(sessionId = s.id, sessionName = s.name) }
                                    message = "Selected ${s.name}"
                                    scope.launch { container.sessionManager.testConnection() }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall, color = colors.text)
                                Text("${s.id} · ${s.status} · ${s.phone ?: "-"}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            }
                            if (s.id == settings.sessionId) StatusChip("SELECTED", Tone.OK)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it.filter { ch -> ch.isLetterOrDigit() || ch == '-' } },
                        label = { Text("New session name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            when (val res = container.api.createSession(newName)) {
                                is ApiResult.Success -> {
                                    container.settings.update { it.copy(sessionId = res.data.id, sessionName = res.data.name) }
                                    message = "Session '${res.data.name}' bana: ${res.data.id}"
                                    load()
                                }
                                is ApiResult.Failure -> message = "❌ ${res.data.let { res.error.oneLine }}"
                            }
                        }
                    }, enabled = settings.mode == AppMode.LIVE) { Text("Create session") }
                }
            }

            SectionLabel("Pairing code (QR ke bina)")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    var phone by remember { mutableStateOf("") }
                    var code by remember { mutableStateOf<String?>(null) }
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it },
                        label = { Text("Phone number (with country code)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            when (val res = container.sessionManager.requestPairingCode(phone)) {
                                is ApiResult.Success -> code = res.data
                                is ApiResult.Failure -> message = "❌ ${res.error.oneLine}"
                            }
                        }
                    }) { Text("Request pairing code") }
                    code?.let {
                        Spacer(Modifier.height(8.dp))
                        CodeBlock("Pairing code: $it\nWhatsApp → Linked devices → Link with phone number")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
