package io.anonero.di

import android.content.Context
import android.content.SharedPreferences
import io.anonero.AnonConfig
import io.anonero.services.AnonWalletHandler
import io.anonero.services.TorService
import io.anonero.services.WalletState
import io.anonero.store.LogRepository
import io.anonero.store.NodesRepository
import io.anonero.ui.home.SendViewModel
import io.anonero.ui.home.settings.LogViewModel
import io.anonero.ui.home.settings.NodeSettingsViewModel
import io.anonero.ui.home.settings.ProxySettingsViewModel
import io.anonero.ui.home.settings.SecureWipeViewModel
import io.anonero.ui.onboard.OnboardViewModel
import io.anonero.ui.viewmodels.AppViewModel
import io.anonero.util.WALLET_PREFERENCES
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module


fun provideWalletSharedPrefs(app: Context): SharedPreferences =
    app.getSharedPreferences(AnonConfig.PREFS, Context.MODE_PRIVATE)

val appModule = module {
    single(named(WALLET_PREFERENCES)) { provideWalletSharedPrefs(androidApplication()) }
    single { TorService(get(named(WALLET_PREFERENCES))) }
    single { WalletState(get(named(WALLET_PREFERENCES))) }
    single { AnonWalletHandler(get(named(WALLET_PREFERENCES)), get(), get()) }
    single { LogRepository(get()) }
    single { NodesRepository(get()) }
    single {
        AppViewModel(get(), get())
    }
    viewModel { OnboardViewModel(get(named(WALLET_PREFERENCES))) }
    viewModel { LogViewModel(get()) }
    viewModel { SendViewModel() }
    viewModel { SecureWipeViewModel(get(), get(named(WALLET_PREFERENCES)), get(), get()) }
    viewModel { NodeSettingsViewModel(get(named(WALLET_PREFERENCES)), get<NodesRepository>()) }
    viewModel { ProxySettingsViewModel(get(), get(), get(named(WALLET_PREFERENCES))) }
}