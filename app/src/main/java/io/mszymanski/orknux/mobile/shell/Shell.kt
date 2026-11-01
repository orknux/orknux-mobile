package io.mszymanski.orknux.mobile.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import io.mszymanski.orknux.mobile.chat.ChatPerspective
import io.mszymanski.orknux.mobile.core.AppSession
import io.mszymanski.orknux.mobile.core.OrknuxClient
import io.mszymanski.orknux.mobile.design.Ork
import io.mszymanski.orknux.mobile.design.OrknuxTheme
import io.mszymanski.orknux.mobile.design.Sky
import io.mszymanski.orknux.mobile.signin.SignInScreen

/** Every perspective this app has. The next one goes here. */
val Perspectives: List<Perspective> = listOf(ChatPerspective)

@Composable
fun OrknuxRoot(session: AppSession) {
    OrknuxTheme {
        val client by session.client.collectAsState()
        val notice by session.notice.collectAsState()
        val signedIn = client
        if (signedIn == null) {
            SignInScreen(session, notice)
        } else {
            // Keyed, so nothing of one server's session outlives it.
            androidx.compose.runtime.key(signedIn) { Shell(session, signedIn) }
        }
    }
}

@Composable
private fun Shell(session: AppSession, client: OrknuxClient) {
    val navController = rememberNavController()
    var userName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(client) { userName = runCatching { session.user(client).name }.getOrNull() }

    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Perspectives.size > 1 && Perspectives.any { route in it.topLevelRoutes }

    Box(Modifier.fillMaxSize()) {
        Sky(Modifier.fillMaxSize(), strength = 0.45f, washAt = 0f)
        Column(Modifier.fillMaxSize()) {
            NavHost(navController, startDestination = Perspectives.first().route, Modifier.weight(1f)) {
                val context = PerspectiveContext(session, client, { userName }, navController)
                Perspectives.forEach { perspective -> perspective.graph(this, context) }
                composable(OPTIONS_ROUTE) { OptionsScreen(session, onBack = { navController.popBackStack() }) }
            }
            if (showBar) {
                HorizontalDivider(color = Ork.border)
                NavigationBar(containerColor = Ork.surface, modifier = Modifier.navigationBarsPadding()) {
                    Perspectives.forEach { perspective ->
                        NavigationBarItem(
                            selected = entry?.destination?.hierarchy?.any { it.route == perspective.route } == true,
                            onClick = {
                                navController.navigate(perspective.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(perspective.icon, null, Modifier.size(20.dp)) },
                            label = { Text(perspective.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Ork.textPrimary,
                                selectedTextColor = Ork.textPrimary,
                                indicatorColor = Ork.surfaceHover,
                                unselectedIconColor = Ork.textSecondary,
                                unselectedTextColor = Ork.textSecondary,
                            ),
                        )
                    }
                }
            }
        }
    }
}
