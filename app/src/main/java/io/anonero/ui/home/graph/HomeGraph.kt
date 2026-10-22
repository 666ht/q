package io.anonero.ui.home.graph

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import io.anonero.AnonConfig
import io.anonero.model.WalletManager
import io.anonero.ui.home.HomeScreenComposable
import io.anonero.ui.home.LockScreen
import io.anonero.ui.home.graph.routes.Home
import io.anonero.ui.home.graph.routes.HomeScreenRoute
import io.anonero.ui.home.graph.routes.LockScreenRoute


fun NavGraphBuilder.homeGraph(navController: NavHostController) {
    // Never call wallet.status from the navigation graph builder: it enters JNI and
    // may wait on wallet2's native lock while refresh/scan work is active. Graph
    // construction runs on the UI thread, so use the in-memory wallet reference to
    // choose the initial route without blocking input/focus dispatch.
    val walletIsOpen = WalletManager.instance?.wallet != null
    val secureWipePending = AnonConfig.context?.let {
        AnonConfig.isSecureWipePending(it)
    } == true
    navigation<Home>(
        startDestination = if (secureWipePending || walletIsOpen) {
            HomeScreenRoute()
        } else {
            LockScreenRoute
        }
    ) {
        composable<HomeScreenRoute> {
            HomeScreenComposable(mainNavController = navController)
        }
        composable<LockScreenRoute> {
            val walletState = org.koin.compose.koinInject<io.anonero.services.WalletState>()
            LockScreen(
                onUnLocked = { _, shortCut ->
                    navController.navigate(HomeScreenRoute()) {
                        popUpTo(LockScreenRoute) {
                            inclusive = true
                        }
                    }
                    if (shortCut != io.anonero.ui.home.LockScreenShortCut.HOME) {
                        walletState.emitUnlockShortcut(shortCut)
                    }
                }
            )
        }
    }
}
