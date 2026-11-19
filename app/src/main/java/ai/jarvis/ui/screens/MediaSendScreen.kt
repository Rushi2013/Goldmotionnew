package ai.jarvis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import ai.jarvis.core.ApiResult
import ai.jarvis.core.AppMode
import ai.jarvis.core.Numbers
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.ChipRow
import ai.jarvis.ui.components.CodeBlock
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch

@Composable
fun MediaSendScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var kind by remember { mutableStateOf("TEXT") }
    var chatId by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var filename by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactNumber by remember { mutableStateOf("") }
    var pollName by remember { mutableStateOf("") }
    var pollOptions by remember { mutableStateOf("") }
    var templateName by remember { mutableStateOf("") }
    var productId by remember { mutableStateOf("") }
    var ptt by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pickedBase64 by remember { mutableStateOf<String?>(null) }
    var pickedMime by remember { mutableStateOf<String?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val pair = uriToBase64(context, uri)
            pickedBase64 = pair?.first
            pickedMime = pair?.second
            pickedName = uri.lastPathSegment?.substringAfterLast('/')
            result = if (pair == null) "❌ File padhi nahi ja saki" else "📎 ${pair.second} · ${pair.first.length / 1365} KB (memory me)"
        }
    }

    val kinds = listOf("TEXT", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "STICKER", "LOCATION", "CONTACT", "POLL", "TEMPLATE", "PRODUCT")

    fun resolvedChatId(): String? {
        val c = chatId.trim()
        if (c.isEmpty()) return null
        return Numbers.toChatId(c, settings.defaultCountryCode) ?: c.takeIf { it.contains('@') }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Column(Modifier.weight(1f)) {
                Text("Media Send", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Text("Ek message, kisi bhi type ka", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            StatusChip(if (settings.mode == AppMode.DEMO) "DEMO" else "LIVE", if (settings.mode == AppMode.DEMO) Tone.WARN else Tone.OK)
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("Type")
            ChipRow(kinds, kind) { kind = it }

            SectionLabel("Target")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = chatId, onValueChange = { chatId = it },
                        label = { Text("chatId or number") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("919812345678 ya 919812345678@c.us") },
                    )
                }
            }

            SectionLabel("Content")
            JarvisCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    when (kind) {
                        "TEXT" -> OutlinedTextField(
                            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                            maxLines = 6, placeholder = { Text("Message (≤4096)") },
                        )
                        "IMAGE", "VIDEO", "STICKER" -> {
                            OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Media URL") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "video/*")) }) {
                                Text(if (pickedBase64 == null) "Gallery se pick karo" else "Pick again")
                            }
                            pickedMime?.let { m ->
                                Text(
                                    "Picked: ${pickedName ?: "file"} · $m · ${(pickedBase64?.length ?: 0) / 1365} KB (memory me, device par save nahi)",
                                    style = MaterialTheme.typography.labelSmall, color = colors.muted,
                                )
                            }
                            if (kind != "STICKER") {
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(value = caption, onValueChange = { caption = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Caption") }, singleLine = true)
                            }
                        }
                        "AUDIO" -> {
                            OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Audio URL") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                                Text(if (pickedBase64 == null) "Audio pick karo" else "Pick again")
                            }
                            pickedMime?.let { m ->
                                Text("Picked: ${pickedName ?: "audio"} · $m", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            }
                            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = ptt, onCheckedChange = { ptt = it })
                                Text("Voice note (ptt)", style = MaterialTheme.typography.bodySmall, color = colors.text)
                            }
                        }
                        "DOCUMENT" -> {
                            OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Document URL") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                                Text(if (pickedBase64 == null) "File pick karo" else "Pick again")
                            }
                            pickedMime?.let { m ->
                                Text("Picked: ${pickedName ?: "file"} · $m", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(value = filename, onValueChange = { filename = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Filename") }, singleLine = true)
                        }
                        "LOCATION" -> {
                            OutlinedTextField(value = lat, onValueChange = { lat = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Latitude") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(value = lon, onValueChange = { lon = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Longitude") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(value = caption, onValueChange = { caption = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Description") }, singleLine = true)
                        }
                        "CONTACT" -> {
                            OutlinedTextField(value = contactName, onValueChange = { contactName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Contact name") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(value = contactNumber, onValueChange = { contactNumber = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Contact number") }, singleLine = true)
                        }
                        "POLL" -> {
                            OutlinedTextField(value = pollName, onValueChange = { pollName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Poll question") }, singleLine = true)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(value = pollOptions, onValueChange = { pollOptions = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Options (comma separated)") }, singleLine = true)
                        }
                        "TEMPLATE" -> {
                            OutlinedTextField(value = templateName, onValueChange = { templateName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Template name") }, singleLine = true)
                        }
                        "PRODUCT" -> {
                            OutlinedTextField(value = productId, onValueChange = { productId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Product id") }, singleLine = true)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val target = resolvedChatId()
                    if (target == null) { result = "chatId/number invalid"; return@Button }
                    scope.launch {
                        busy = true
                        result = null
                        if (settings.mode == AppMode.DEMO) {
                            result = "DEMO — ye request LIVE mode me jaati.\n\n" + requestPreview(kind, target)
                            busy = false
                            return@launch
                        }
                        val sid = settings.sessionId
                        val res: ApiResult<Any> = when (kind) {
                            "TEXT" -> container.api.sendText(sid, target, text).mapAny()
                            "IMAGE" -> container.api.sendImage(sid, target, url = url.ifBlank { null }, base64 = pickedBase64, mimetype = pickedMime, caption = caption.ifBlank { null }).mapAny()
                            "VIDEO" -> container.api.sendVideo(sid, target, url = url.ifBlank { null }, base64 = pickedBase64, mimetype = pickedMime, caption = caption.ifBlank { null }).mapAny()
                            "AUDIO" -> container.api.sendAudio(sid, target, url = url.ifBlank { null }, base64 = pickedBase64, mimetype = pickedMime, ptt = ptt).mapAny()
                            "DOCUMENT" -> container.api.sendDocument(sid, target, url = url.ifBlank { null }, base64 = pickedBase64, mimetype = pickedMime, filename = filename.ifBlank { pickedName }).mapAny()
                            "STICKER" -> container.api.sendSticker(sid, target, url = url.ifBlank { null }, base64 = pickedBase64, mimetype = pickedMime ?: "image/webp").mapAny()
                            "LOCATION" -> container.api.sendLocation(
                                sid, target, lat.toDoubleOrNull() ?: 0.0, lon.toDoubleOrNull() ?: 0.0, caption.ifBlank { null },
                            ).mapAny()
                            "CONTACT" -> container.api.sendContact(sid, target, contactName, contactNumber).mapAny()
                            "POLL" -> container.api.sendPoll(
                                sid, target, pollName, pollOptions.split(',').map { it.trim() }.filter { it.isNotBlank() },
                            ).mapAny()
                            "TEMPLATE" -> container.api.sendTemplate(sid, target, templateName = templateName).mapAny()
                            "PRODUCT" -> container.api.sendProduct(sid, target, productId).mapAnyOutcome()
                            else -> ApiResult.Failure(ai.jarvis.core.ApiError(0, "Unknown type"))
                        }
                        result = when (res) {
                            is ApiResult.Success -> {
                                val d = res.data
                                if (d is ai.jarvis.data.api.SendResultDto) {
                                    "✅ Gateway ne accept kiya (messageId: ${d.messageId}).\nDelivery confirm nahi — tick ack par update hoga."
                                } else "✅ Accepted (status ${res.status})"
                            }
                            is ApiResult.Failure -> "❌ ${res.error.headline}\n${res.error.message}\n\n${res.error.rawBody.take(300)}"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
            ) { Text(if (busy) "Sending…" else "Send $kind") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { result = requestPreview(kind, resolvedChatId() ?: chatId) }) { Text("Show request JSON") }

            result?.let {
                Spacer(Modifier.height(10.dp))
                CodeBlock(it, maxLines = 20)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun requestPreview(kind: String, chatId: String): String = when (kind) {
    "TEXT" -> "POST /messages/send-text\n{\"chatId\":\"$chatId\",\"text\":\"…\"}"
    "IMAGE" -> "POST /messages/send-image\n{\"chatId\":\"$chatId\",\"url\":\"…\",\"caption\":\"…\"}"
    "VIDEO" -> "POST /messages/send-video\n{\"chatId\":\"$chatId\",\"url\":\"…\"}"
    "AUDIO" -> "POST /messages/send-audio\n{\"chatId\":\"$chatId\",\"url\":\"…\",\"ptt\":true}"
    "DOCUMENT" -> "POST /messages/send-document\n{\"chatId\":\"$chatId\",\"url\":\"…\",\"filename\":\"…\"}"
    "STICKER" -> "POST /messages/send-sticker\n{\"chatId\":\"$chatId\",\"url\":\"…\",\"mimetype\":\"image/webp\"}"
    "LOCATION" -> "POST /messages/send-location\n{\"chatId\":\"$chatId\",\"latitude\":0,\"longitude\":0}"
    "CONTACT" -> "POST /messages/send-contact\n{\"chatId\":\"$chatId\",\"contactName\":\"…\",\"contactNumber\":\"…\"}"
    "POLL" -> "POST /messages/send-poll\n{\"chatId\":\"$chatId\",\"name\":\"…\",\"options\":[\"a\",\"b\"]}"
    "TEMPLATE" -> "POST /messages/send-template\n{\"chatId\":\"$chatId\",\"templateName\":\"…\"}"
    "PRODUCT" -> "POST /messages/send-product\n{\"chatId\":\"$chatId\",\"productId\":\"…\"}"
    else -> "?"
}

private fun ApiResult<ai.jarvis.data.api.SendResultDto>.mapAny(): ApiResult<Any> = when (this) {
    is ApiResult.Success -> ApiResult.Success(data, status)
    is ApiResult.Failure -> this
}

private fun ApiResult<ai.jarvis.data.api.ApiOutcome>.mapAnyOutcome(): ApiResult<Any> = when (this) {
    is ApiResult.Success -> ApiResult.Success(data, status)
    is ApiResult.Failure -> this
}

/**
 * Reads a user-picked content Uri straight into base64. Nothing is copied to app storage —
 * the bytes go from the SAF stream to the request body and are then dropped, which is what
 * "device me store nahi hona chahiye" requires.
 */
private fun uriToBase64(context: android.content.Context, uri: android.net.Uri): Pair<String, String>? =
    runCatching {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) to mime
    }.getOrNull()
