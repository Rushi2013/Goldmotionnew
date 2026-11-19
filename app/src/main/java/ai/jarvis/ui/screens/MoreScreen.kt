package ai.jarvis.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ForwardToInbox
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.BanRiskBanner
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.nav.Routes
import ai.jarvis.ui.theme.LocalJarvisColors

private data class Entry(val icon: ImageVector, val label: String, val route: String, val hint: String = "")

private data class Group(val title: String, val entries: List<Entry>)

@Composable
fun MoreScreen(nav: NavHostController, container: AppContainer) {
    val colors = LocalJarvisColors.current
    val pending by container.database.escalationDao().observePendingCount().collectAsState(initial = 0)

    val groups = listOf(
        Group("Assist", listOf(
            Entry(Icons.Filled.ForwardToInbox, "Approvals", Routes.APPROVALS, if (pending > 0) "$pending pending" else ""),
            Entry(Icons.Filled.SmartToy, "Auto-reply Lab", Routes.LAB),
        )),
        Group("Messaging", listOf(
            Entry(Icons.Filled.ChatBubble, "Media Send", Routes.MEDIA_SEND),
            Entry(Icons.Filled.CameraAlt, "Status, Views & Channels", Routes.STATUS_HUB),
            Entry(Icons.Filled.Group, "Groups", Routes.GROUPS),
            Entry(Icons.Filled.Campaign, "Channels", Routes.CHANNELS),
            Entry(Icons.Filled.Label, "Labels", Routes.LABELS),
            Entry(Icons.Filled.Description, "Templates", Routes.TEMPLATES),
            Entry(Icons.Filled.Store, "Catalog", Routes.CATALOG),
        )),
        Group("Directory", listOf(
            Entry(Icons.Filled.Contacts, "Contacts", Routes.CONTACTS),
            Entry(Icons.Filled.Phone, "Number Checker", Routes.NUMBER_CHECKER),
            Entry(Icons.Filled.Search, "Search", Routes.SEARCH),
            Entry(Icons.Filled.BarChart, "Statistics", Routes.STATS),
        )),
        Group("Automation", listOf(
            Entry(Icons.Filled.Webhook, "Webhooks", Routes.WEBHOOKS),
            Entry(Icons.Filled.AutoAwesome, "Server automation rules", Routes.RULES),
            Entry(Icons.Filled.QrCode, "Session / QR", Routes.SESSIONS),
        )),
        Group("System", listOf(
            Entry(Icons.Filled.Settings, "Settings", Routes.SETTINGS),
            Entry(Icons.Filled.Dataset, "Dev Tools", Routes.DEVTOOLS),
            Entry(Icons.Filled.Code, "Intent Test", Routes.INTENT_TEST),
        )),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("More", style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Text("Poora WhatsApp console — sab features", style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Spacer(Modifier.height(8.dp))
        StatusChip("OpenWA 0.30.3 · 164 API paths wired", Tone.INFO)

        groups.forEach { group ->
            SectionLabel(group.title)
            JarvisCard(Modifier.fillMaxWidth()) {
                Column { group.entries.forEach { entry -> EntryRow(entry) { nav.navigate(entry.route) } } }
            }
        }
        Spacer(Modifier.height(12.dp))
        BanRiskBanner()
        Spacer(Modifier.height(12.dp))
        Text(
            "Plugins / Infra / API keys: admin-only routes hain. Dev Tools → API Console se koi bhi route " +
                "call kar sakte ho (operator key par admin routes 403 denge — sahi behaviour hai).",
            style = MaterialTheme.typography.bodySmall, color = colors.muted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EntryRow(entry: Entry, onClick: () -> Unit) {
    val colors = LocalJarvisColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.icon, contentDescription = entry.label, tint = colors.cyan, modifier = Modifier.width(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(entry.label, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.weight(1f))
        if (entry.hint.isNotBlank()) {
            Text(entry.hint, style = MaterialTheme.typography.labelSmall, color = colors.warn)
        }
    }
}
