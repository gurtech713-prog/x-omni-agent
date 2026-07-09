package com.omniclaw.app.core.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.omniclaw.app.core.theme.Motion
import com.omniclaw.app.core.theme.OmniMono
import com.omniclaw.app.ui.chat.ChatScreen
import com.omniclaw.app.ui.memory.MemoryScreen
import com.omniclaw.app.ui.schedule.ScheduleScreen
import com.omniclaw.app.ui.sessions.SessionsScreen
import com.omniclaw.app.ui.settings.SettingsScreen

private sealed class TopRoute(val route: String, val label: String, val icon: ImageVector, val iconSelected: ImageVector) {
    data object Chat     : TopRoute("chat?sessionId={sessionId}", "Chat", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome)
    data object Sessions : TopRoute("sessions", "Sessions", Icons.Outlined.Hub,        Icons.Filled.Hub)
    data object Memory   : TopRoute("memory",   "Memory",   Icons.Outlined.Bookmark,   Icons.Filled.Bookmark)
    data object Schedule : TopRoute("schedule", "Schedule", Icons.Outlined.Schedule,   Icons.Filled.Schedule)
    data object Settings : TopRoute("settings", "Settings", Icons.Outlined.Settings,   Icons.Filled.Settings)
}

// Material Design guidelines cap NavigationBar at 5 destinations. The app
// previously had 6 (Chat, Sessions, Skills, Memory, Schedule, Settings),
// which caused label truncation on narrow devices. Skills has been merged
// into the Memory tab — the Memory screen now shows learned lessons, memory
// entries, AND bundled skills. The Skills route is kept for deep-link
// compatibility but is no longer a bottom-nav destination.
private val TopRoutes = listOf(
    TopRoute.Chat,
    TopRoute.Sessions,
    TopRoute.Memory,
    TopRoute.Schedule,
    TopRoute.Settings,
)

@Composable
fun OmniApp() {
    val nav = rememberNavController()
    val stack by nav.currentBackStackEntryAsState()
    val current = stack?.destination?.route ?: TopRoute.Chat.route

    // BackHandler: pressing back from a non-Chat tab returns to Chat (the
    // start destination). Pressing back from Chat exits the app (default
    // behavior). Previously back exited from ANY tab, which surprised users.
    androidx.activity.compose.BackHandler(enabled = current != TopRoute.Chat.route) {
        nav.navigate(TopRoute.Chat.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            OmniBottomBar(
                current = current,
                onSelect = { route ->
                    nav.navigate(route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = TopRoute.Chat.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(
                route = TopRoute.Chat.route,
                arguments = listOf(
                    androidx.navigation.navArgument("sessionId") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")
                FadeInScreen { ChatScreen(sessionId = sessionId) }
            }
            composable(TopRoute.Sessions.route) {
                FadeInScreen {
                    SessionsScreen(onSelectSession = { id ->
                        nav.navigate("chat?sessionId=$id") {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    })
                }
            }
            composable(TopRoute.Memory.route)   { FadeInScreen { MemoryScreen() } }
            composable(TopRoute.Schedule.route) { FadeInScreen { ScheduleScreen() } }
            composable(TopRoute.Settings.route) { FadeInScreen { SettingsScreen() } }
        }
    }
}

/** Wraps each destination with a subtle fade-in for smooth tab transitions. */
@Composable
private fun FadeInScreen(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(Motion.DurationMedium, easing = Motion.StandardEasing)),
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}

@Composable
private fun OmniBottomBar(current: String, onSelect: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        TopRoutes.forEach { r ->
            val selected = current == r.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(r.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) r.iconSelected else r.icon,
                        contentDescription = r.label,
                    )
                },
                label = {
                    Text(
                        text = r.label,
                        fontFamily = OmniMono,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.background,
                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    // The indicator pill must contrast with the bar background.
                    // Previously it was set to `background` (same as
                    // containerColor), making the selected-tab indicator
                    // invisible. Now it uses `onBackground` so the selected
                    // tab gets a filled pill behind the icon — the standard
                    // Material 3 indicator pattern.
                    indicatorColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }
    }
}
