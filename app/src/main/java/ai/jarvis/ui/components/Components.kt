package ai.jarvis.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.jarvis.ui.theme.Dimens
import ai.jarvis.ui.theme.LocalJarvisColors

@Composable
fun ScreenScaffoldTitle(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = LocalJarvisColors.current.text)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = LocalJarvisColors.current.muted)
            }
        }
        actions()
    }
}

/** Truthful status chip. Colour is never the only signal — the text always says it. */
@Composable
fun StatusChip(
    text: String,
    tone: Tone = Tone.NEUTRAL,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalJarvisColors.current
    val color = when (tone) {
        Tone.OK -> colors.success
        Tone.WARN -> colors.warn
        Tone.ERROR -> colors.danger
        Tone.INFO -> colors.cyan
        Tone.NEUTRAL -> colors.muted
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

enum class Tone { OK, WARN, ERROR, INFO, NEUTRAL }

@Composable
fun JarvisCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalJarvisColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(Dimens.cardRadius))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(Dimens.cardRadius))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}

@Composable
fun SectionLabel(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LocalJarvisColors.current.muted,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(title: String, body: String, action: @Composable (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = LocalJarvisColors.current.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = LocalJarvisColors.current.muted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
fun ErrorState(title: String, detail: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        StatusChip(title, Tone.ERROR)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalJarvisColors.current.muted)
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry", color = LocalJarvisColors.current.cyan) }
        }
    }
}

@Composable
fun LoadingState(label: String = "Load ho raha hai…") {
    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
            color = LocalJarvisColors.current.cyan,
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalJarvisColors.current.muted)
    }
}

@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, maxLines: Int = 12) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF060A0F))
            .border(1.dp, LocalJarvisColors.current.border, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(
            code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp),
            color = LocalJarvisColors.current.text.copy(alpha = 0.9f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The signature orb: three rotating arcs + breathing core. Draws from a cache, no big blurs. */
@Composable
fun JarvisOrb(
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    listening: Boolean = false,
    amplitude: Float = 0f,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalJarvisColors.current
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        modifier
            .size(size)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val breath = if (listening) pulse + amplitude.coerceIn(0f, 1f) * 0.12f else pulse

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.cyan.copy(alpha = 0.34f),
                        colors.blue.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = r * breath,
                ),
                radius = r * breath,
                center = center,
            )

            rotate(spin) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, colors.cyan, Color.Transparent)),
                    startAngle = 0f, sweepAngle = 220f, useCenter = false,
                    topLeft = Offset(center.x - r * 0.78f, center.y - r * 0.78f),
                    size = Size(r * 1.56f, r * 1.56f),
                    style = Stroke(width = 2.2f, cap = StrokeCap.Round),
                )
            }
            rotate(-spin * 1.6f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, colors.blue, Color.Transparent)),
                    startAngle = 40f, sweepAngle = 160f, useCenter = false,
                    topLeft = Offset(center.x - r * 0.64f, center.y - r * 0.64f),
                    size = Size(r * 1.28f, r * 1.28f),
                    style = Stroke(width = 1.6f, cap = StrokeCap.Round),
                )
            }
            drawCircle(
                color = colors.text.copy(alpha = 0.9f),
                radius = if (listening) 7f + amplitude * 9f else 5.5f,
                center = center,
            )
            drawCircle(
                color = colors.cyan.copy(alpha = 0.55f),
                radius = r * 0.55f, center = center,
                style = Stroke(width = 1f),
            )
        }
    }
}

@Composable
fun WaveformBars(amplitudes: List<Float>, modifier: Modifier = Modifier, color: Color = LocalJarvisColors.current.cyan) {
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        if (amplitudes.isEmpty()) return@Canvas
        val barWidth = size.width / (amplitudes.size * 2f)
        amplitudes.forEachIndexed { i, amp ->
            val h = (amp.coerceIn(0.05f, 1f)) * size.height
            val x = i * barWidth * 2f + barWidth / 2f
            drawLine(
                color = color,
                start = Offset(x, (size.height - h) / 2f),
                end = Offset(x, (size.height + h) / 2f),
                strokeWidth = barWidth.coerceAtLeast(1.5f),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun ChipRow(items: List<String>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items.size) { idx ->
            val item = items[idx]
            val isSel = item == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSel) LocalJarvisColors.current.cyan.copy(alpha = 0.18f) else LocalJarvisColors.current.surfaceElevated)
                    .border(1.dp, if (isSel) LocalJarvisColors.current.cyan.copy(alpha = 0.6f) else LocalJarvisColors.current.border, RoundedCornerShape(50))
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    item,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSel) LocalJarvisColors.current.cyan else LocalJarvisColors.current.text,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Honest DEMO watermark — required on every simulated surface. */
@Composable
fun DemoBanner(modifier: Modifier = Modifier, text: String = "DEMO — message actually nahi bheja gaya") {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalJarvisColors.current.warn.copy(alpha = 0.12f))
            .border(1.dp, LocalJarvisColors.current.warn.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("DEMO", style = MaterialTheme.typography.labelSmall, color = LocalJarvisColors.current.warn, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = LocalJarvisColors.current.warn.copy(alpha = 0.9f))
    }
}

@Composable
fun BanRiskBanner(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalJarvisColors.current.danger.copy(alpha = 0.10f))
            .border(1.dp, LocalJarvisColors.current.danger.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(
            "⚠ WhatsApp ban-risk: OpenWA unofficial (baileys/whatsapp-web.js) hai. Dedicated number use karo, " +
                "naye number ko warm up karo, strangers ko cold-blast mat karo. Auto-reply sirf tab chalta hai " +
                "jab JARVIS chal raha ho.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalJarvisColors.current.danger.copy(alpha = 0.9f),
        )
    }
}
