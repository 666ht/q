package io.anonero.ui.home

import AnonNeroTheme
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.anonero.R
import io.anonero.model.Subaddress
import io.anonero.ui.components.MainBottomNavigation
import io.anonero.ui.home.addresses.SubAddressDetailScreen
import io.anonero.ui.home.addresses.SubAddressesScreen
import io.anonero.ui.home.graph.routes.CoinsScreenRoute
import io.anonero.ui.home.graph.routes.HomeScreenRoute
import io.anonero.ui.home.graph.routes.ProxySettingsRoute
import io.anonero.ui.home.graph.routes.ReceiveRoute
import io.anonero.ui.home.graph.routes.ReviewTransactionRoute
import io.anonero.ui.home.graph.routes.SecureWipeRoute
import io.anonero.ui.home.graph.routes.SendScreenRoute
import io.anonero.ui.home.graph.routes.SettingsExportBackUp
import io.anonero.ui.home.graph.routes.SettingsLogs
import io.anonero.ui.home.graph.routes.SettingsNodeRoute
import io.anonero.ui.home.graph.routes.SettingsRoute
import io.anonero.ui.home.graph.routes.SettingsViewSeedRoute
import io.anonero.ui.home.graph.routes.SubAddressesRoute
import io.anonero.ui.home.graph.routes.TransactionDetailRoute
import io.anonero.ui.home.graph.routes.TransactionsRoute
import io.anonero.ui.home.settings.ExportBackUp
import io.anonero.ui.home.settings.LogViewer
import io.anonero.ui.home.settings.NodeSettings
import io.anonero.ui.home.settings.ProxySettings
import io.anonero.ui.home.settings.SecureWipe
import io.anonero.ui.home.settings.SeedSettingsPage
import io.anonero.ui.home.settings.SettingsPage
import io.anonero.ui.home.spend.ReviewTransactionScreen
import io.anonero.ui.onboard.graph.LandingScreenRoute
import io.anonero.util.isIgnoringBatteryOptimizations
import org.koin.compose.koinInject
import io.anonero.services.WalletState

@OptIn(ExperimentalSharedTransitionApi::class)
@SuppressLint("BatteryLife")
@Composable
fun HomeScreenComposable(modifier: Modifier = Modifier, mainNavController: NavHostController?) {

    var showBottomNavigation by remember { mutableStateOf(true) }
    val bottomNavController = rememberNavController()
    val context = LocalContext.current
    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: TransactionsRoute
    var showBatteryManagerDialog by remember { mutableStateOf(false) }
    val walletState = koinInject<WalletState>()

    val startDestination: Any = TransactionsRoute
    
    LaunchedEffect(Unit) {
        walletState.unlockShortcut.collect { shortcut ->
            when (shortcut) {
                LockScreenShortCut.HOME -> bottomNavController.navigate(TransactionsRoute) {
                    popUpTo(TransactionsRoute) { inclusive = true }
                    launchSingleTop = true
                }
                LockScreenShortCut.RECEIVE -> bottomNavController.navigate(ReceiveRoute) {
                    popUpTo(TransactionsRoute) { inclusive = false }
                    launchSingleTop = true
                }
                LockScreenShortCut.SEND -> bottomNavController.navigate(SendScreenRoute(address = "")) {
                    popUpTo(TransactionsRoute) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }
    
    LaunchedEffect(currentRoute) {
        if (getRoutes().find { it.getRouteAsString == currentRoute } != null) {
            showBottomNavigation = true
        }
    }

    LaunchedEffect(true) {
        if (!context.isIgnoringBatteryOptimizations()) {
            showBatteryManagerDialog = true
        }
    }

    if (showBatteryManagerDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryManagerDialog = false
            },
            title = {
                Text(stringResource(R.string.battery_optimization_title))
            },
            text = {
                Text(
                    stringResource(R.string.battery_optimization_message)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = Intent()
                        intent.action = ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        intent.data = "package:${context.packageName}".toUri()
                        context.startActivity(intent)
                        showBatteryManagerDialog = false
                    }
                ) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBatteryManagerDialog = false
                    }
                ) {
                    Text(
                        stringResource(R.string.cancel),
                        style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSecondary)
                    )
                }
            },
            modifier = Modifier.border(
                1.dp,
                color = MaterialTheme.colorScheme.onSecondary.copy(
                    alpha = .2f
                ),
                shape = MaterialTheme.shapes.medium,
            ),
            containerColor = MaterialTheme.colorScheme.secondary,
        )
    }

    SharedTransitionLayout {
        Scaffold(
            contentWindowInsets = WindowInsets(
                top = 0.dp, bottom = 0.dp
            ), bottomBar = {
                AnimatedVisibility(
                    visible = showBottomNavigation,
                    enter = slideInVertically(
                        initialOffsetY = { it }, // Slide in from the bottom
                        animationSpec = tween(durationMillis = 500)
                    ),
                    exit = slideOutVertically(
                        targetOffsetY = { it }, // Slide out to the bottom
                        animationSpec = tween(durationMillis = 500)
                    )
                ) {
                    MainBottomNavigation(
                        currentRoute = currentRoute,
                        onTabSelected = {
                            bottomNavController.navigate(it.route) {
                                popUpTo(bottomNavController.graph.startDestinationId)
                                launchSingleTop = true
                            }
                        }
                    )
                }

            }) { paddingValues ->
            Box(modifier = modifier.padding(0.dp)) {
                NavHost(bottomNavController, startDestination = startDestination, exitTransition = {
                    fadeOut(animationSpec = tween(340))
                }, enterTransition = {
                    fadeIn(animationSpec = tween(340))
                }) {
                    composable<TransactionsRoute> {
                        TransactionScreen(
                            modifier = Modifier.padding(paddingValues),
                            onItemClick = {
                                showBottomNavigation = false
                                it.hash?.let { paymentId ->
                                    bottomNavController.navigate(
                                        TransactionDetailRoute(
                                            transactionId = paymentId
                                        )
                                    )
                                }
                            },
                            navigateToShortCut = {
                                when (it) {
                                    LockScreenShortCut.HOME -> {}
                                    LockScreenShortCut.RECEIVE -> {
                                        bottomNavController.navigate(ReceiveRoute)
                                    }

                                    LockScreenShortCut.SEND -> {
                                        bottomNavController.navigate(SendScreenRoute(""))
                                    }
                                }
                            },
                            navigateTo = {
                                if(it is CoinsScreenRoute){
                                    showBottomNavigation = false
                                }
                                bottomNavController.navigate(it)
                            },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@composable
                        )
                    }
                    composable<ReceiveRoute> {
                        ReceiveScreen(
                            modifier = Modifier.padding(paddingValues),
                            onBackPress = {
                                bottomNavController.popBackStack()
                            }, navigateToSubAddresses = {
                                showBottomNavigation = false
                                bottomNavController.navigate(SubAddressesRoute)
                            })
                    }
                    composable<TransactionDetailRoute> { navBackStackEntry ->
                        val txDetailRoute = navBackStackEntry.toRoute<TransactionDetailRoute>()
                        TransactionDetailScreen(
                            transactionId = txDetailRoute.transactionId,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@composable,
                            onBackPress = {
                                bottomNavController.popBackStack()
                            },
                            modifier = Modifier.padding(paddingValues),
                        )
                    }
                    composable<SendScreenRoute> { backStackEntry ->
                        val params = backStackEntry.toRoute<SendScreenRoute>()
                        SendScreen(
                            navigateToHome = {
                                bottomNavController.popBackStack(
                                    route = TransactionsRoute,
                                    inclusive = false
                                )
                            },
                            navigateToReview = {
                                bottomNavController.navigate(it)
                            },
                            modifier = Modifier.padding(
                                bottom = paddingValues.calculateBottomPadding()
                            ),
                            onBackPress = {
                                bottomNavController.popBackStack()
                            },
                            paymentUri = params,
                        )
                    }
                    composable<ReviewTransactionRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<ReviewTransactionRoute>()
                        ReviewTransactionScreen(
                            route,
                            modifier = Modifier.padding(paddingValues),
                            navigateToHome = {
                                bottomNavController.popBackStack(
                                    route = TransactionsRoute,
                                    inclusive = false
                                )
                            },
                            onFinished = {
                                bottomNavController.popBackStack(
                                    route = TransactionsRoute,
                                    inclusive = false
                                )
                            },
                            onBackPressed = {
                                bottomNavController.popBackStack()
                            },
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsPage(
                            modifier = Modifier.padding(paddingValues),
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            }, navigateTo = {
                                showBottomNavigation = false
                                bottomNavController.navigate(it)
                            }
                        )
                    }
                    composable<SettingsViewSeedRoute> {
                        SeedSettingsPage(
                            onBackPress = {
                                bottomNavController.popBackStack()
                            }
                        )
                    }
                    composable<SettingsExportBackUp> {
                        ExportBackUp(
                            onBackPress = {
                                bottomNavController.popBackStack()
                            }
                        )
                    }
                    composable<SettingsLogs> {
                        LogViewer(
                            onBackPress = {
                                bottomNavController.popBackStack()
                            }
                        )
                    }
                    composable<CoinsScreenRoute> {
                        CoinsScreen(
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            },
                            navigateToSpend = {
                                showBottomNavigation = true
                                bottomNavController.navigate(it)
                            }
                        )
                    }
                    composable<ProxySettingsRoute> {
                        ProxySettings(
                            onBackPress = {
                                bottomNavController.popBackStack()
                            }
                        )
                    }
                    composable<SecureWipeRoute> {
                        SecureWipe(
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            },
                            requestClearScreen = {
                                showBottomNavigation = it
                            },
                            goToHome = {
                                mainNavController?.navigate(LandingScreenRoute) {
                                    popUpTo(LandingScreenRoute) {
                                        inclusive = false
                                    }
                                }
                            }
                        )
                    }
                    composable<SettingsNodeRoute> {
                        NodeSettings(
                            onBackPress = {
                                bottomNavController.popBackStack()
                            }
                        )
                    }
                    composable<SubAddressesRoute> {
                        SubAddressesScreen(
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            }, navigateToDetails = {
                                bottomNavController.navigate(it)
                            },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@composable
                        )
                    }
                    composable<Subaddress> { backStackEntry ->
                        val subAddress = backStackEntry.toRoute<Subaddress>()
                        SubAddressDetailScreen(
                            subAddress = subAddress,
                            onBackPress = {
                                bottomNavController.popBackStack()
                            },
                            onTransactionClick = {
                                it.hash?.let { paymentId ->
                                    bottomNavController.navigate(
                                        TransactionDetailRoute(
                                            transactionId = paymentId
                                        )
                                    )
                                }
                            },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@composable
                        )
                    }
                }
            }
        }
    }
}


@Preview(device = "id:pixel_8")
@Composable
private fun HomeScreenComposablePrev() {
    AnonNeroTheme {
        HomeScreenComposable(mainNavController = null)
    }
}
