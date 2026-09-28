package io.anonero.ui.onboard.graph

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.ui.home.graph.routes.Home
import io.anonero.ui.home.settings.LogViewer
import io.anonero.ui.home.settings.ProxySettings
import io.anonero.ui.onboard.Mode
import io.anonero.ui.onboard.OnboardLoadingComposable
import io.anonero.ui.onboard.OnboardViewModel
import io.anonero.ui.onboard.OnboardingWelcome
import io.anonero.ui.onboard.PinSetup
import io.anonero.ui.onboard.RestoreWallet
import io.anonero.ui.onboard.SeedSetup
import io.anonero.ui.onboard.SetupNodeComposable
import io.anonero.ui.onboard.SetupPassphrase
import io.anonero.ui.onboard.restore.RestorePreview
import io.anonero.ui.onboard.viewonly.RestoreFromKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable


@Serializable
data object LandingScreenRoute

@Serializable
data object OnboardingWelcomeScreen

@Serializable
data object OnboardingProxyScreen

@Serializable
data object OnboardPassPhraseScreen

@Serializable
data object OnboardLogsScreen

@Serializable
data class OnboardLoading(val message: String)

@Serializable
data class OnboardSeedScreen(val seed: String)

@Serializable
data object OnboardPinScreen

@Serializable
data object OnboardNodeSetupScreen

@Serializable
data object OnboardRestoreScreen

@Serializable
data class OnboardRestorePreviewScreen(val backUpPath: String)

@Serializable
data object OnboardImportKeysScreen

//onboarding
fun NavGraphBuilder.onboardingGraph(
    navController: NavHostController,
    onboardViewModel: OnboardViewModel
) {


    navigation<LandingScreenRoute>(
        startDestination = OnboardingWelcomeScreen,
    ) {

        dialog<OnboardLoading>(
            dialogProperties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            OnboardLoadingComposable(loadingState = it.toRoute())
        }
        composable<OnboardingWelcomeScreen> {
            OnboardingWelcome(
                onRestoreClick = {
                    onboardViewModel.setMode(Mode.RESTORE)
                    if (it != null) {
                        navController.navigate(OnboardRestorePreviewScreen(it))
                    } else {
                        navController.navigate(OnboardRestoreScreen)
                    }
                },
                onCreateClick = {
                    onboardViewModel.setMode(Mode.CREATE)
                    navController.navigate(OnboardNodeSetupScreen)
                },
                onRestoreFromKeys = {
                    navController.navigate(OnboardImportKeysScreen)
                },
                onProxySettings = {
                    navController.navigate(OnboardingProxyScreen)
                },
                onLogsScreen = {
                    navController.navigate(OnboardLogsScreen)
                }
            )
        }
        composable<OnboardingProxyScreen> {
            ProxySettings(
                onBackPress = {
                    navController.navigateUp()
                },
            )
        }
        composable<OnboardNodeSetupScreen> {
            SetupNodeComposable(
                onBackPressed = {
                    navController.navigateUp()
                },
                oNextPressed = {
                    if (AnonConfig.viewOnly) {
                        navController.navigate(OnboardPinScreen)
                    } else {
                        navController.navigate(OnboardPassPhraseScreen)
                    }
                }
            )
        }
        composable<OnboardRestoreScreen> {
            RestoreWallet(
                onboardViewModel = onboardViewModel,
                onBackPressed = {
                    navController.navigateUp()
                },
                oNextPressed = {
                    onboardViewModel.setRestorePayload(it)
                    navController
                        .navigate(OnboardNodeSetupScreen)
                }
            )
        }
        composable<OnboardRestorePreviewScreen> { navBackStackEntry ->
            val backUpPath = navBackStackEntry.toRoute<OnboardRestorePreviewScreen>()
            RestorePreview(
                backUpPath = backUpPath.backUpPath,
                onBackPressed = {
                    navController.navigateUp()
                },
                navigateTo = {
                    navController.navigate(it)
                },
                oNextPressed = {
                    navController.navigate(
                        Home
                    ) {
                        popUpTo(LandingScreenRoute)
                    }
                }
            )
        }
        composable<OnboardPassPhraseScreen> {
            SetupPassphrase(
                onBackPressed = {
                    navController.navigateUp()
                },
                oNextPressed = {
                    onboardViewModel.setPassPhrase(it)
                    navController.navigate(OnboardPinScreen)
                }
            )
        }

        composable<OnboardLogsScreen>() {
            LogViewer(
                onBackPress = {
                    navController.navigateUp()
                }
            )
        }

        composable<OnboardPinScreen> {
            var showErrorMessage by remember { mutableStateOf<String?>(null) }
            val context = LocalContext.current
            OnboardErrorDialog(
                showErrorMessage = showErrorMessage,
                isRestoreMode = onboardViewModel.getMode() == Mode.RESTORE,
                onClose = {
                    showErrorMessage = null
                    navController.popBackStack()
                },
                onShowLogs = {
                    showErrorMessage = null
                    navController.popBackStack()
                    navController.navigate(OnboardLogsScreen)
                }
            )
            PinSetup(
                onNext = { pin ->
                    var loadingMessage = context.getString(R.string.creating_wallet)
                    if (onboardViewModel.getMode() == Mode.RESTORE) {
                        loadingMessage = context.getString(R.string.restoring_wallet)
                    }
                    navController.navigate(OnboardLoading(loadingMessage))
                    onboardViewModel.viewModelScope.launch {
                        if (AnonConfig.viewOnly) {
                            onboardViewModel.createViewOnly(pin)
                        } else {
                            if (onboardViewModel.getMode() == Mode.RESTORE) {
                                withContext(Dispatchers.IO) {
                                    onboardViewModel.restoreFromSeed(pin)
                                }
                            } else {
                                onboardViewModel.create(pin)
                            }
                        }
                        delay(600)
                    }.invokeOnCompletion {
                        if (it == null) {
                            if (AnonConfig.viewOnly) {
                                navController.navigate(
                                    Home
                                ) {
                                    popUpTo(LandingScreenRoute)
                                }
                            } else {
                                if (onboardViewModel.getMode() == Mode.RESTORE) {
                                    navController.navigate(
                                        Home
                                    ) {
                                        popUpTo(LandingScreenRoute)
                                    }
                                } else {
                                    navController.navigate(OnboardSeedScreen(onboardViewModel.getSeed()))
                                }
                            }
                        } else {
                            navController.popBackStack()
                            showErrorMessage = it.message ?: "Unknown error";
                        }
                    }
                }
            )
        }
        composable<OnboardSeedScreen> { navBackStackEntry ->
            val state = navBackStackEntry.toRoute<OnboardSeedScreen>()
            SeedSetup(
                seed = state.seed
                    .split(" ").toList(),
                oNextPressed = {
                    navController.navigate(
                        Home
                    ) {
                        popUpTo(LandingScreenRoute)
                    }
                },
                onBackPressed = {
                    navController.navigate("")
                }
            )
        }
        composable<OnboardImportKeysScreen> {
            RestoreFromKeys(
                oNextPressed = {
                    onboardViewModel.setNeroPayload(it)
                    navController.navigate(OnboardNodeSetupScreen)
                },
                onBackPressed = {
                    navController.navigateUp()
                }
            )
        }
    }
}


@Composable
fun OnboardErrorDialog(
    showErrorMessage: String?,
    isRestoreMode: Boolean,
    onClose: () -> Unit,
    onShowLogs: () -> Unit
) {
    if (showErrorMessage != null) {
        AlertDialog(
            onDismissRequest = onClose,
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    onClick = onClose
                ) { Text(stringResource(R.string.close)) }
            },
            modifier = Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(alpha = .2f),
                    shape = MaterialTheme.shapes.medium,
                ),
            dismissButton = {
                Button(
                    onClick = onShowLogs,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onSecondary
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    )
                ) {
                    Text(
                        stringResource(R.string.logs),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f)
                        )
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.secondary,
            title = {
                Text(
                    text = stringResource(
                        R.string.wallet,
                        stringResource(R.string.unable_to),
                        if (isRestoreMode) stringResource(
                            R.string.restore
                        ) else stringResource(R.string.create)
                    ),
                )
            },
            text = {
                Text(
                    text = showErrorMessage,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = MaterialTheme.colorScheme.error,
                    )
                )
            }
        )
    }
}