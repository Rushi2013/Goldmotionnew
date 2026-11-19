package ai.jarvis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.jarvis.data.api.MessageDto
import ai.jarvis.ui.theme.LocalJarvisColors
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext

/**
 * Renders real media inside a message bubble.
 *
 * Before this existed every attachment collapsed to a text chip ("🖼 Image"), which is exactly
 * what the user reported: "messages me messages nahi dikh rahe and do image bhi nahi dikh rahe".
 *
 * Media is fetched **in memory** (Coil for images, on-demand bytes for the rest) and is never
 * written to app storage, so nothing accumulates on the device.
 */
@Composable
fun MediaPreview(
    msg: MessageDto,
    authHeader: String?,
    onOpen: (() -> Unit)? = null,
) {
    val colors = LocalJarvisColors.current
    val context = LocalContext.current

    val type = msg.type.lowercase()
    if (type in setOf("text", "", "poll", "location", "contact", "revoked")) {
        IconBadge(msg) { onOpen?.invoke() }
        return
    }

    val remote = remember(msg.mediaPath) {
        msg.mediaPath?.takeIf { it.isNotBlank() }?.let { path ->
            if (path.startsWith("http")) path else null
        }
    }

    when (type) {
        "image", "sticker", "video" -> {
            val model = remote ?: msg.mediaPath
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceElevated)
                    .clickable(enabled = onOpen != null) { onOpen?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                if (model != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(model)
                            .apply { if (!authHeader.isNullOrBlank() && remote != null) addHeader("X-API-Key", authHeader) }
                            .crossfade(true)
                            .build(),
                        contentDescription = msg.body ?: msg.type,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                    )
                    if (type == "video") {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(colors.bubbleIn.copy(alpha = 0.75f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play video", tint = colors.text)
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Image, contentDescription = null, tint = colors.muted)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Media gateway par uplink nahi hai",
                            style = MaterialTheme.typography.labelSmall, color = colors.muted,
                        )
                        Text(
                            "Tap karke download try karo",
                            style = MaterialTheme.typography.labelSmall, color = colors.cyan,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        else -> IconBadge(msg) { onOpen?.invoke() }
    }
}

@Composable
private fun IconBadge(msg: MessageDto, onOpen: () -> Unit) {
    val colors = LocalJarvisColors.current
    val (icon, label) = when (msg.type.lowercase()) {
        "audio", "voice", "ptt" -> Icons.Filled.Mic to "Voice note"
        "document" -> Icons.Filled.PictureAsPdf to (msg.mediaPath?.substringAfterLast('/') ?: "Document")
        "location" -> Icons.Filled.PlayArrow to "Location"
        "contact" -> Icons.Filled.PlayArrow to "Contact"
        "poll" -> Icons.Filled.PlayArrow to "Poll"
        "revoked" -> Icons.Filled.PlayArrow to "Deleted"
        else -> null to null
    }
    if (label == null) return
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.cyan.copy(alpha = 0.10f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon ?: Icons.Filled.Download, contentDescription = null, tint = colors.cyan, modifier = Modifier.size(18.dp))
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = colors.cyan,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(180.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
}

/** Small spinner used while a media blob is being fetched into memory. */
@Composable
fun MediaLoading() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(6.dp))
        Text("Loading…", style = MaterialTheme.typography.labelSmall, color = LocalJarvisColors.current.muted)
    }
}

/** Guard so a failed media fetch is shown as text, never as a silent blank bubble. */
@Composable
fun MediaError(message: String) {
    val colors = LocalJarvisColors.current
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (open) message else "Media load nahi hua (tap)",
            style = MaterialTheme.typography.labelSmall,
            color = colors.warn,
            modifier = Modifier.clickable { open = !open },
        )
    }
}
