package ai.jarvis.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.jarvis.JarvisApp
import ai.jarvis.ui.screens.ChatsScreen
import ai.jarvis.ui.screens.HomeScreen
import ai.jarvis.ui.screens.MoreScreen
import ai.jarvis.ui.screens.OnboardingScreen
import ai.jarvis.ui.screens.ApprovalsScreen
import ai.jarvis.ui.screens.AutoReplyLabScreen
import ai.jarvis.ui.screens.AutoReplyScreen
import ai.jarvis.ui.screens.BulkScreen
import ai.jarvis.ui.screens.CatalogScreen
import ai.jarvis.ui.screens.ChannelDetailScreen
import ai.jarvis.ui.screens.ChannelsScreen
import ai.jarvis.ui.screens.ContactsScreen
import ai.jarvis.ui.screens.DevToolsScreen
import ai.jarvis.ui.screens.GroupsScreen
import ai.jarvis.ui.screens.IntentTestScreen
import ai.jarvis.ui.screens.LabelsScreen
import ai.jarvis.ui.screens.MediaSendScreen
import ai.jarvis.ui.screens.NumberCheckerScreen
import ai.jarvis.ui.screens.RulesScreen
import ai.jarvis.ui.screens.SearchScreen
import ai.jarvis.ui.screens.SessionsScreen
import ai.jarvis.ui.screens.SettingsScreen
import ai.jarvis.ui.screens.StatsScreen
import ai.jarvis.ui.screens.StatusScreen
import ai.jarvis.ui.screens.TemplatesScreen
import ai.jarvis.ui.screens.ThreadScreen
import ai.jarvis.ui.screens.WebhooksScreen
import ai.jarvis.ui.theme.LocalJarvisColors

object Routes {
    const val HOME = "home"
    const val CHATS = "chats"
    const val AUTOREPLY = "autoreply"
    const val BULK = "bulk"
    const val MORE = "more"
    const val ONBOARDING = "onboarding"
    const val APPROVALS = "approvals"
    const val LAB = "lab"
    const val THREAD = "thread/{chatId}"
    const val MEDIA_SEND = "media"
    const val STATUS = "status"
    const val STATUS_HUB = "statushub"
    const val GROUPS = "groups"
    const val CHANNELS = "channels"
    const val CHANNEL_DETAIL = "channel/{channelId}"
    const val LABELS = "labels"
    const val TEMPLATES = "templates"
    const val CATALOG = "catalog"
    const val CONTACTS = "contacts"
    const val NUMBER_CHECKER = "numberchecker"
    const val SEARCH = "search"
    const val WEBHOOKS = "webhooks"
    const val RULES = "rules"
    const val STATS = "stats"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val DEVTOOLS = "devtools"
    const val INTENT_TEST = "intenttest"

    fun thread(chatId: String) = "thread/${java.net.URLEncoder.encode(chatId, "UTF-8")}"
    fun channel(channelId: String) = "channel/${java.net.URLEncoder.encode(channelId, "UTF-8")}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Filled.Dashboard),
    Tab(Routes.CHATS, "Chats", Icons.Filled.Chat),
    Tab(Routes.AUTOREPLY, "Auto", Icons.Filled.AutoAwesome),
    Tab(Routes.BULK, "Bulk", Icons.Filled.Send),
    Tab(Routes.MORE, "More", Icons.Filled.MoreHoriz),
)

@Composable
fun JarvisRoot(
    initialRoute: String? = null,
    requestPermission: (String) -> Unit = {},
    hasPermission: (String) -> Boolean = { true },
) {
    val context = LocalContext.current
    val app = context.applicationContext as JarvisApp
    val container = app.container
    val settings by container.settings.settings.collectAsState()
    val nav = rememberNavController()

    if (!settings.onboardingDone) {
        OnboardingScreen(
            container = container,
            onDone = {
                container.settings.update { it.copy(onboardingDone = true) }
                nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            },
        )
        return
    }

    OrbScaffold(nav = nav, container = container, initialRoute = initialRoute, requestPermission = requestPermission, hasPermission = hasPermission)
}

@Composable
private fun OrbScaffold(
    nav: NavHostController,
    container: ai.jarvis.di.AppContainer,
    initialRoute: String?,
    requestPermission: (String) -> Unit,
    hasPermission: (String) -> Boolean,
) {
    val colors = LocalJarvisColors.current
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBar = currentRoute in tabs.map { it.route }

    Scaffold(
        containerColor = colors.amoled,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) nav.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.cyan,
                                selectedTextColor = colors.cyan,
                                unselectedIconColor = colors.muted,
                                unselectedTextColor = colors.muted,
                                indicatorColor = colors.cyan.copy(alpha = 0.10f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.amoled)
                .padding(padding)
        ) {
            NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
                composable(Routes.HOME) {
                    HomeScreen(container, nav, requestPermission = requestPermission, hasPermission = hasPermission)
                }
                composable(Routes.CHATS) { ChatsScreen(container, nav) }
                composable(Routes.AUTOREPLY) { AutoReplyScreen(container, nav) }
                composable(Routes.BULK) { BulkScreen(container, nav) }
                composable(Routes.MORE) { MoreScreen(nav, container) }
                composable(Routes.ONBOARDING) { OnboardingScreen(container) { nav.navigate(Routes.HOME) } }
                composable(Routes.APPROVALS) { ApprovalsScreen(container, nav) }
                composable(Routes.LAB) { AutoReplyLabScreen(container, nav) }
                composable(Routes.THREAD) { entry ->
                    val raw = entry.arguments?.getString("chatId").orEmpty()
                    ThreadScreen(container, java.net.URLDecoder.decode(raw, "UTF-8"), nav)
                }
                composable(Routes.MEDIA_SEND) { MediaSendScreen(container, nav) }
                composable(Routes.STATUS) { StatusScreen(container, nav) }
                composable(Routes.STATUS_HUB) { StatusHubScreen(container, nav) }
                composable(Routes.GROUPS) { GroupsScreen(container, nav) }
                composable(Routes.CHANNELS) { ChannelsScreen(container, nav) }
                composable(Routes.CHANNEL_DETAIL) { entry ->
                    val raw = entry.arguments?.getString("channelId").orEmpty()
                    ChannelDetailScreen(container, java.net.URLDecoder.decode(raw, "UTF-8"), nav)
                }
                composable(Routes.LABELS) { LabelsScreen(container, nav) }
                composable(Routes.TEMPLATES) { TemplatesScreen(container, nav) }
                composable(Routes.CATALOG) { CatalogScreen(container, nav) }
                composable(Routes.CONTACTS) { ContactsScreen(container, nav) }
                composable(Routes.NUMBER_CHECKER) { NumberCheckerScreen(container, nav) }
                composable(Routes.SEARCH) { SearchScreen(container, nav) }
                composable(Routes.WEBHOOKS) { WebhooksScreen(container, nav) }
                composable(Routes.RULES) { RulesScreen(container, nav) }
                composable(Routes.STATS) { StatsScreen(container, nav) }
                composable(Routes.SESSIONS) { SessionsScreen(container, nav) }
                composable(Routes.SETTINGS) { SettingsScreen(container, nav) }
                composable(Routes.DEVTOOLS) { DevToolsScreen(container, nav) }
                composable(Routes.INTENT_TEST) { IntentTestScreen(container, nav) }
            }
        }
    }
}
