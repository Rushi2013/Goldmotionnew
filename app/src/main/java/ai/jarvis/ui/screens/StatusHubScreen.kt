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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ai.jarvis.core.ApiResult
import ai.jarvis.core.AppMode
import ai.jarvis.data.api.ChannelDto
import ai.jarvis.data.api.StatusDto
import ai.jarvis.di.AppContainer
import ai.jarvis.ui.components.EmptyState
import ai.jarvis.ui.components.ErrorState
import ai.jarvis.ui.components.JarvisCard
import ai.jarvis.ui.components.LoadingState
import ai.jarvis.ui.components.SectionLabel
import ai.jarvis.ui.components.StatusChip
import ai.jarvis.ui.components.Tone
import ai.jarvis.ui.nav.Routes
import ai.jarvis.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch

/**
 * WhatsApp-style Status screen.
 *
 * Three tabs, exactly like WhatsApp's own Stories surface:
 *   • MY STATUS   — what I posted, plus **views / likes / who interacted**.
 *   • CONTACTS    — other people's statuses, viewable, likeable, with their interaction data.
 *   • CHANNELS    — the channels I joined, plus public-channel search and join.
 *
 * Honesty rule for interaction counters: OpenWA only reports receipts on engines that implement
 * them. When a count is absent we render "—" (no data) instead of a fake 0, because a fake 0 is
 * indistinguishable from "nobody looked at it".
 */
@Composable
fun StatusHubScreen(container: AppContainer, nav: NavHostController) {
    val colors = LocalJarvisColors.current
    val settings by container.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }
    var mine by remember { mutableStateOf<List<StatusDto>>(emptyList()) }
    var theirs by remember { mutableStateOf<List<StatusDto>>(emptyList()) }
    var channels by remember { mutableStateOf<List<ChannelDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var showComposer by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var detailFor by remember { mutableStateOf<StatusDto?>(null) }

    val live = settings.mode != AppMode.DEMO

    fun loadAll() {
        scope.launch {
            loading = true
            error = null
            if (!live) {
                loading = false
                return@launch
            }
            if (settings.sessionId.isBlank()) {
                error = "Session id set nahi hai — Settings → WhatsApp / OpenWA me daalo."
                loading = false
                return@launch
            }
            when (val res = container.api.statusList(settings.sessionId)) {
                is ApiResult.Success -> {
                    val all = res.data.statuses
                    // Own statuses are the ones the gateway flags as mine, or whose author JID
                    // matches our own session number when the flag is missing.
                    val own = all.filter { it.isOwn }
                    val rest = all.filterNot { it.isOwn }
                    if (own.isEmpty() && rest.isNotEmpty()) {
                        mine = emptyList()
                        theirs = rest
                    } else {
                        mine = own
                        theirs = rest
                    }
                }
                is ApiResult.Failure -> error = "${res.error.headline}: ${res.error.oneLine}"
            }
            when (val res = container.api.listChannels(settings.sessionId)) {
                is ApiResult.Success -> channels = res.data
                is ApiResult.Failure -> if (error == null) {
                    error = "Channels: ${res.error.headline}: ${res.error.oneLine}"
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadAll() }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (nav.previousBackStackEntry != null) nav.popBackStack() else nav.navigate(Routes.HOME) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Column(Modifier.weight(1f)) {
                Text("Status", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Text(
                    "${mine.size} apne · ${theirs.size} contacts",
                    style = MaterialTheme.typography.labelSmall, color = colors.muted,
                )
            }
            StatusChip(if (live) "LIVE" else "DEMO", if (live) Tone.OK else Tone.WARN)
            IconButton(onClick = { showComposer = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Naya status", tint = colors.cyan)
            }
        }

        TabRow(
            selectedTabIndex = tab,
            containerColor = colors.surface,
            contentColor = colors.cyan,
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf("My status", "Contacts", "Channels").forEachIndexed { idx, label ->
                Tab(
                    selected = tab == idx,
                    onClick = { tab = idx },
                    text = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    selectedContentColor = colors.cyan,
                    unselectedContentColor = colors.muted,
                )
            }
        }

        note?.let {
            Text(
                it, style = MaterialTheme.typography.labelSmall, color = colors.warn,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        when {
            loading -> LoadingState("Statuses load ho rahe hain…")
            error != null -> ErrorState("Status load nahi hua", error!!) { loadAll() }
            else -> when (tab) {
                0 -> MyStatusTab(
                    items = mine,
                    live = live,
                    sessionId = settings.sessionId,
                    container = container,
                    onNote = { note = it },
                    onOpen = { detailFor = it },
                    onChanged = { loadAll() },
                    onCompose = { showComposer = true },
                )
                1 -> ContactStatusTab(
                    items = theirs,
                    live = live,
                    sessionId = settings.sessionId,
                    container = container,
                    onNote = { note = it },
                    onOpen = { detailFor = it },
                    onChanged = { loadAll() },
                    onRefresh = { loadAll() },
                )
                else -> ChannelsTab(
                    channels = channels,
                    live = live,
                    sessionId = settings.sessionId,
                    container = container,
                    onNote = { note = it },
                    onChanged = { loadAll() },
                    onOpenChannel = { nav.navigate(Routes.channel(it)) },
                    onJoin = { showJoin = true },
                )
            }
        }
    }

    if (showComposer) {
        StatusComposerDialog(
            live = live,
            onDismiss = { showComposer = false },
            onSubmitText = { text, bg ->
                scope.launch {
                    if (!live) { note = "DEMO — status post nahi hua."; return@launch }
                    note = when (val r = container.api.postTextStatus(settings.sessionId, text, bg)) {
                        is ApiResult.Success -> "✅ Text status post ho gaya"
                        is ApiResult.Failure -> "❌ ${r.error.oneLine}"
                    }
                    showComposer = false
                    loadAll()
                }
            },
            onSubmitImage = { base64, mime, caption ->
                scope.launch {
                    if (!live) { note = "DEMO — status post nahi hua."; return@launch }
                    note = when (val r = container.api.postImageStatus(settings.sessionId, base64 = base64, mimetype = mime, caption = caption)) {
                        is ApiResult.Success -> "✅ Image status post ho gaya"
                        is ApiResult.Failure -> "❌ ${r.error.oneLine}"
                    }
                    showComposer = false
                    loadAll()
                }
            },
        )
    }

    if (showJoin) {
        JoinChannelDialog(
            live = live,
            onDismiss = { showJoin = false },
            onJoinCode = { code ->
                scope.launch {
                    if (!live) { note = "DEMO — join nahi hua."; return@launch }
                    note = when (val r = container.api.subscribeChannel(settings.sessionId, code)) {
                        is ApiResult.Success -> "✅ Channel join ho gaya"
                        is ApiResult.Failure -> "❌ ${r.error.oneLine}"
                    }
                    showJoin = false
                    loadAll()
                }
            },
        )
    }

    detailFor?.let { status ->
        StatusDetailDialog(
            status = status,
            live = live,
            sessionId = settings.sessionId,
            container = container,
            onDismiss = { detailFor = null },
            onNote = { note = it },
            onChanged = { loadAll() },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// My status
// ---------------------------------------------------------------------------------------------
@Composable
private fun MyStatusTab(
    items: List<StatusDto>,
    live: Boolean,
    sessionId: String,
    container: AppContainer,
    onNote: (String) -> Unit,
    onOpen: (StatusDto) -> Unit,
    onChanged: () -> Unit,
    onCompose: () -> Unit,
) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()

    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text("Abhi koi status nahi", style = MaterialTheme.typography.titleMedium, color = colors.text)
            Spacer(Modifier.height(6.dp))
            Text(
                "24 ghante tak dikhne wala status post karo. Views, likes aur kaun-kaun dekha — " +
                    "ye sab yahin milega.",
                style = MaterialTheme.typography.bodySmall, color = colors.muted,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onCompose,
                colors = ButtonDefaults.buttonColors(containerColor = colors.cyan, contentColor = colors.onPrimary),
            ) { Text("Status add karo") }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(items, key = { it.id }) { s ->
            JarvisCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(s.kind.name, Tone.INFO)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            relativeAge(s.epochMs),
                            style = MaterialTheme.typography.labelSmall, color = colors.muted,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            scope.launch {
                                if (!live) { onNote("DEMO — delete nahi hua."); return@launch }
                                when (val r = container.api.deleteStatus(sessionId, s.id)) {
                                    is ApiResult.Success -> { onNote("🗑 Status delete ho gaya"); onChanged() }
                                    is ApiResult.Failure -> onNote("❌ ${r.error.oneLine}")
                                }
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = colors.danger) }
                    }
                    s.bodyText?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    // ---- the interaction strip the user asked for -------------------------
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        InteractionStat(Icons.Filled.Visibility, s.resolvedViews, "views")
                        InteractionStat(Icons.Filled.Favorite, s.resolvedLikes, "likes")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onOpen(s) }) { Text("Interactions") }
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractionStat(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int?, label: String) {
    val colors = LocalJarvisColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = colors.cyan, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            // "—" means the engine did not report a number. Never invent a 0.
            text = count?.toString() ?: "—",
            style = MaterialTheme.typography.labelMedium,
            color = if (count == null) colors.muted else colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        Text(" $label", style = MaterialTheme.typography.labelSmall, color = colors.muted)
    }
}

// ---------------------------------------------------------------------------------------------
// Contacts' statuses
// ---------------------------------------------------------------------------------------------
@Composable
private fun ContactStatusTab(
    items: List<StatusDto>,
    live: Boolean,
    sessionId: String,
    container: AppContainer,
    onNote: (String) -> Unit,
    onOpen: (StatusDto) -> Unit,
    onChanged: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()

    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            EmptyState(
                "Koi contact status nahi",
                "Jab contact status daalega to yahan dikhega — tap karke dekh sakte ho, like kar sakte ho.",
            )
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
        }
        return
    }

    // The rail at the top: one bubble per contact, WhatsApp style.
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { "rail-${it.id}" }) { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (s.likedByMe == true) 2.dp else 3.dp,
                            color = if (s.likedByMe == true) colors.danger else colors.cyan,
                            shape = CircleShape,
                        )
                        .clickable { onOpen(s) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.displayContact.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium, color = colors.text,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    s.displayContact, style = MaterialTheme.typography.labelSmall, color = colors.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(items, key = { it.id }) { s ->
            JarvisCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.displayContact, style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f))
                        Text(relativeAge(s.epochMs), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    }
                    s.bodyText?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                if (!live) { onNote("DEMO — view mark nahi hua."); return@launch }
                                container.api.viewStatus(sessionId, s.id)
                                onNote("👁 ${s.displayContact} ka status dekha")
                                onChanged()
                            }
                        }) { Text("View", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                if (!live) { onNote("DEMO — like nahi hua."); return@launch }
                                when (val r = container.api.likeStatus(sessionId, s.id)) {
                                    is ApiResult.Success -> { onNote("❤️ Like bhej diya"); onChanged() }
                                    is ApiResult.Failure -> onNote("❌ ${r.error.oneLine}")
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.danger)
                            Spacer(Modifier.width(4.dp))
                            Text("Like", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onOpen(s) }) { Text("Open") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Channels
// ---------------------------------------------------------------------------------------------
@Composable
private fun ChannelsTab(
    channels: List<ChannelDto>,
    live: Boolean,
    sessionId: String,
    container: AppContainer,
    onNote: (String) -> Unit,
    onChanged: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onJoin: () -> Unit,
) {
    val colors = LocalJarvisColors.current
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ChannelDto>>(emptyList()) }

    fun runSearch() {
        scope.launch {
            if (!live) { onNote("DEMO — search nahi chalega."); return@launch }
            if (search.isBlank()) { results = emptyList(); return@launch }
            searching = true
            // The gateway exposes public channels through the general search route.
            when (val res = container.api.searchChannels(sessionId, search.trim())) {
                is ApiResult.Success -> results = parseChannelSearch(res.data)
                is ApiResult.Failure -> onNote("❌ Search: ${res.error.oneLine}")
            }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Public channels search karo") },
                singleLine = true,
            )
            IconButton(onClick = { runSearch() }) { Icon(Icons.Filled.Search, contentDescription = "Search", tint = colors.cyan) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onJoin) { Text("Invite code se join") }
            OutlinedButton(onClick = onChanged) { Text("Refresh") }
        }

        if (searching) LoadingState("Search ho raha hai…")

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (results.isNotEmpty()) {
                item { SectionLabel("Search results") }
                items(results, key = { "res-${it.id}" }) { ch ->
                    ChannelRow(ch) {
                        scope.launch {
                            if (!live) { onNote("DEMO — join nahi hua."); return@launch }
                            val code = ch.inviteCode
                            if (code.isNullOrBlank()) {
                                onNote("❌ Is channel ka invite code nahi mila")
                            } else {
                                when (val r = container.api.subscribeChannel(sessionId, code)) {
                                    is ApiResult.Success -> { onNote("✅ ${ch.name} join ho gaya"); onChanged() }
                                    is ApiResult.Failure -> onNote("❌ ${r.error.oneLine}")
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            item { SectionLabel("Joined channels (${channels.size})") }
            if (channels.isEmpty()) {
                item {
                    EmptyState(
                        "Abhi koi channel nahi",
                        "Public channel search karo ya invite code se join karo — joined channels yahan niche dikhenge.",
                    )
                }
            }
            items(channels, key = { it.id }) { ch ->
                ChannelRow(ch, onClick = { onOpenChannel(ch.id) })
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: ChannelDto, onClick: (() -> Unit)? = null) {
    val colors = LocalJarvisColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable(enabled = onClick != null) { onClick?.invoke() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Text(ch.name.take(1).uppercase(), color = colors.blue, style = MaterialTheme.typography.titleSmall) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, style = MaterialTheme.typography.titleSmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ch.verified == true) { Spacer(Modifier.width(4.dp)); StatusChip("✓", Tone.OK) }
            }
            ch.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ch.subscriberCount?.let {
                Text("$it subscribers", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
        }
        if (onClick == null) {
            TextButton(onClick = { onClick?.invoke() }) { Text("Join") }
        }
    }
}

/** `/search?type=channels` returns a JsonElement whose shape varies; decode defensively. */
private fun parseChannelSearch(el: kotlinx.serialization.json.JsonElement): List<ChannelDto> {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    val array = when (el) {
        is kotlinx.serialization.json.JsonArray -> el
        is kotlinx.serialization.json.JsonObject -> {
            val inner = el["channels"] ?: el["data"] ?: el["results"]
            inner as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        }
        else -> return emptyList()
    }
    return array.mapNotNull { runCatching { json.decodeFromJsonElement(ChannelDto.serializer(), it) }.getOrNull() }
}

// ---------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------
@Composable
private fun StatusDetailDialog(
    status: StatusDto,
    live: Boolean,
    sessionId: String,
    container: AppContainer,
    onDismiss: () -> Unit,
    onNote: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = LocalJarvisColors.current
    var viewers by remember { mutableStateOf(status.viewers) }
    var likers by remember { mutableStateOf(status.likedBy) }
    var supported by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(status.id) {
        if (!live || sessionId.isBlank()) return@LaunchedEffect
        busy = true
        // Receipts are engine-dependent; a 501 means "not supported", not "zero viewers".
        when (val res = container.api.statusViewers(sessionId, status.id)) {
            is ApiResult.Success -> viewers = parseViewers(res.data)
            is ApiResult.Failure -> if (res.error.isUnsupported) supported = false
        }
        when (val res = container.api.statusLikes(sessionId, status.id)) {
            is ApiResult.Success -> likers = parseViewers(res.data)
            is ApiResult.Failure -> if (res.error.isUnsupported) supported = false
        }
        busy = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(status.displayContact) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                status.bodyText?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.text)
                    Spacer(Modifier.height(8.dp))
                }
                Text(relativeAge(status.epochMs), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InteractionStat(Icons.Filled.Visibility, status.resolvedViews ?: viewers.size.takeIf { viewers.isNotEmpty() }, "views")
                    InteractionStat(Icons.Filled.Favorite, status.resolvedLikes ?: likers.size.takeIf { likers.isNotEmpty() }, "likes")
                }
                Spacer(Modifier.height(10.dp))
                if (!supported) {
                    Text(
                        "Is gateway engine par status receipts support nahi hai (HTTP 501). " +
                            "Numbers q nahi bhar sakte — jhooth nahi bolenge.",
                        style = MaterialTheme.typography.labelSmall, color = colors.warn,
                    )
                } else if (busy) {
                    LoadingState("Interactions aa rahe hain…")
                } else {
                    SectionLabel("Dekhne wale (${viewers.size})")
                    if (viewers.isEmpty()) {
                        Text("Koi data nahi", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    } else {
                        viewers.forEach { v ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(colors.cyan.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                    Text(v.label.take(1).uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.cyan)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(v.label, style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Like karne wale (${likers.size})")
                    if (likers.isEmpty()) {
                        Text("Koi like nahi", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    } else {
                        likers.forEach { v ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Favorite, contentDescription = null, tint = colors.danger, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(v.label, style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!status.isOwn) {
                TextButton(onClick = {
                    scope.launch {
                        if (!live) { onNote("DEMO — like nahi hua."); return@launch }
                        onNote(
                            when (val r = container.api.likeStatus(sessionId, status.id)) {
                                is ApiResult.Success -> "❤️ Like bhej diya"
                                is ApiResult.Failure -> "❌ ${r.error.oneLine}"
                            }
                        )
                        onChanged()
                    }
                }) { Text("Like") }
            } else {
                TextButton(onClick = {
                    scope.launch {
                        if (!live) { onNote("DEMO — delete nahi hua."); return@launch }
                        onNote(
                            when (val r = container.api.deleteStatus(sessionId, status.id)) {
                                is ApiResult.Success -> "🗑 Delete ho gaya"
                                is ApiResult.Failure -> "❌ ${r.error.oneLine}"
                            }
                        )
                        onChanged()
                        onDismiss()
                    }
                }) { Text("Delete", color = colors.danger) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun StatusComposerDialog(
    live: Boolean,
    onDismiss: () -> Unit,
    onSubmitText: (String, String) -> Unit,
    onSubmitImage: (String, String, String?) -> Unit,
) {
    val colors = LocalJarvisColors.current
    var text by remember { mutableStateOf("") }
    var bg by remember { mutableStateOf("#075E54") }
    var imageBase64 by remember { mutableStateOf("") }
    var mime by remember { mutableStateOf("image/jpeg") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Naya status") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    label = { Text("Status text / caption") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bg, onValueChange = { bg = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Background hex (text status)") },
                )
                Spacer(Modifier.height(12.dp))
                Text("Image status (base64)", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                OutlinedTextField(
                    value = imageBase64, onValueChange = { imageBase64 = it },
                    modifier = Modifier.fillMaxWidth().height(76.dp), maxLines = 3,
                    placeholder = { Text("base64 paste karo…") },
                )
                OutlinedTextField(
                    value = mime, onValueChange = { mime = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("MIME type") },
                )
                if (!live) {
                    Text("DEMO mode — kuch bhi post nahi hoga.", style = MaterialTheme.typography.labelSmall, color = colors.warn)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { if (text.isNotBlank()) onSubmitText(text.trim(), bg.trim()) }) { Text("Post text") }
                TextButton(onClick = { if (imageBase64.isNotBlank()) onSubmitImage(imageBase64.trim(), mime.trim(), text.takeIf { it.isNotBlank() }) }) {
                    Text("Post image")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun JoinChannelDialog(
    live: Boolean,
    onDismiss: () -> Unit,
    onJoinCode: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val colors = LocalJarvisColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Channel join karo") },
        text = {
            Column {
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Invite code") },
                    placeholder = { Text("https://whatsapp.com/channel/… ka code") },
                )
                Text(
                    "Public channel ka invite link ho to uska 23-char code paste karo.",
                    style = MaterialTheme.typography.labelSmall, color = colors.muted,
                )
                if (!live) Text("DEMO mode", style = MaterialTheme.typography.labelSmall, color = colors.warn)
            }
        },
        confirmButton = { TextButton(onClick = { if (code.isNotBlank()) onJoinCode(code.trim()) }) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------------------------
private fun parseViewers(el: kotlinx.serialization.json.JsonElement): List<ai.jarvis.data.api.StatusViewerDto> {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    val array = when (el) {
        is kotlinx.serialization.json.JsonArray -> el
        is kotlinx.serialization.json.JsonObject -> {
            val inner = el["viewers"] ?: el["likes"] ?: el["data"] ?: el["results"]
            inner as? kotlinx.serialization.json.JsonArray
        }
        else -> null
    } ?: return emptyList()
    return array.mapNotNull { runCatching { json.decodeFromJsonElement(ai.jarvis.data.api.StatusViewerDto.serializer(), it) }.getOrNull() }
}

/** "3 min pehle" style label. */
private fun relativeAge(epochMs: Long?): String {
    if (epochMs == null) return ""
    val diff = System.currentTimeMillis() - epochMs
    if (diff < 0) return "abhi"
    val mins = diff / 60_000
    return when {
        mins < 1 -> "abhi"
        mins < 60 -> "$mins min pehle"
        mins < 1440 -> "${mins / 60} ghante pehle"
        else -> "${mins / 1440} din pehle"
    }
}
