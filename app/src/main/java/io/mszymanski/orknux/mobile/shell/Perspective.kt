package io.mszymanski.orknux.mobile.shell

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import io.mszymanski.orknux.mobile.core.AppSession
import io.mszymanski.orknux.mobile.core.OrknuxClient

/** What a perspective is given to draw itself with. */
class PerspectiveContext(
    val session: AppSession,
    val client: OrknuxClient,
    /** Read while drawing: it arrives after the graph is built, and the graph is only built once. */
    val userName: () -> String?,
    val navController: NavController,
)

/**
 * One of the product's top-level areas, as in the web client's top bar: Chat,
 * Flow, Workspace and so on. Adding one means writing its screens and listing it
 * in [Perspectives]; the shell draws the bar once there are two to choose between.
 */
class Perspective(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** The routes whose screen shows the bar; a conversation takes the whole height. */
    val topLevelRoutes: Set<String>,
    val graph: NavGraphBuilder.(PerspectiveContext) -> Unit,
)
