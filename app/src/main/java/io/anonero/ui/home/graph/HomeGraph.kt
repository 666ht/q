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
    val walletStatus = WalletManager.instance?.wallet?.status?.isOk
    val secureWipePending = AnonConfig.context?.let {
        AnonConfig.isSecureWipePending(it)
    } == true
    navigation<Home>(
        startDestination = if (secureWipePending || walletStatus == true) {
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
