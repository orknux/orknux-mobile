package io.mszymanski.orknux.mobile.chat

import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import io.mszymanski.orknux.mobile.design.OrkIcons
import io.mszymanski.orknux.mobile.shell.OPTIONS_ROUTE
import io.mszymanski.orknux.mobile.shell.Perspective

private const val LIST = "chat/list"
private const val NEW = "new"

val ChatPerspective = Perspective(
    route = "chat",
    label = "Chat",
    icon = OrkIcons.Message,
    topLevelRoutes = setOf(LIST),
) { context ->
    navigation(startDestination = LIST, route = "chat") {
        composable(LIST) {
            val api = remember(context.client) { ChatApi(context.client) }
            val model: ChatListViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ChatListViewModel(api, context.session.lastWorkspace) { context.session.lastWorkspace = it } }
                },
            )
            ChatListScreen(
                model,
                context.userName(),
                onOpen = { workspace, chat -> context.navController.navigate("chat/$workspace/$chat") },
                onNew = { workspace -> context.navController.navigate("chat/$workspace/$NEW") },
                onOptions = { context.navController.navigate(OPTIONS_ROUTE) },
                onSignOut = context.session::signOut,
            )
        }
        composable(
            "chat/{workspace}/{chat}",
            listOf(navArgument("workspace") { type = NavType.StringType }, navArgument("chat") { type = NavType.StringType }),
        ) { entry ->
            val workspace = entry.arguments?.getString("workspace").orEmpty()
            val chat = entry.arguments?.getString("chat")?.takeUnless { it == NEW }
            val api = remember(context.client) { ChatApi(context.client) }
            val model: ConversationViewModel = viewModel(
                factory = viewModelFactory { initializer { ConversationViewModel(api, workspace, chat, context.session.chatCache(context.client)) } },
            )
            ConversationScreen(
                model,
                base = context.client.base,
                workspaceId = workspace,
                client = context.client,
                engineOf = { context.session.speechEngine },
                listenOf = { context.session.listenEngine },
                thinkingPauseOf = { context.session.thinkingPauseMs },
                thinkingPitchOf = { context.session.thinkingPitchHz },
                speedOf = { context.session.speechSpeed },
                onSpeedChosen = { context.session.speechSpeed = it },
                onBack = { context.navController.popBackStack() },
            )
        }
    }
}
