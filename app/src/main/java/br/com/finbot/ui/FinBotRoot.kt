package br.com.finbot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.R
import br.com.finbot.ui.onboarding.OnboardingScreen
import br.com.finbot.ui.chat.ChatScreen
import br.com.finbot.ui.settings.SettingsScreen
import br.com.finbot.ui.transactions.TransactionsTab
import br.com.finbot.ui.bills.BillsTab
import br.com.finbot.ui.reports.ReportsScreen

enum class MainTab(val label: String, val icon: Int, val comingInStage: String?) {
    CHAT("Chat", R.drawable.ic_tab_chat, null),
    TRANSACTIONS("Lançamentos", R.drawable.ic_tab_list, null),
    BILLS("Contas", R.drawable.ic_tab_bills, null),
    REPORTS("Relatórios", R.drawable.ic_tab_reports, null),
    SETTINGS("Ajustes", R.drawable.ic_tab_settings, null),
}

@Composable
fun FinBotRoot(
    tabRequest: MainTab? = null,
    onTabRequestHandled: () -> Unit = {},
    locked: Boolean = false,
    onUnlockRequest: () -> Unit = {},
    viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val loaded = config
    if (loaded == null) {
        Box(Modifier.fillMaxSize())
    } else if (!loaded.onboardingCompleted) {
        val errors by viewModel.onboardingErrors.collectAsStateWithLifecycle()
        OnboardingScreen(errors = errors, onComplete = viewModel::completeOnboarding)
    } else if (loaded.security.appLock && locked) {
        // Nada com dados financeiros é composto enquanto bloqueado.
        LockScreen(onUnlockRequest)
    } else {
        MainScaffold(tabRequest, onTabRequestHandled)
    }
}

@Composable
private fun MainScaffold(tabRequest: MainTab?, onTabRequestHandled: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(MainTab.CHAT) }
    // Toque numa notificação: abre a aba pedida.
    LaunchedEffect(tabRequest) {
        if (tabRequest != null) {
            selected = tabRequest
            onTabRequestHandled()
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets: o imePadding do chat desconta a barra inferior já reservada.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val stage = selected.comingInStage
            when {
                stage != null -> ComingSoonScreen(title = selected.label, stage = stage)
                selected == MainTab.CHAT -> ChatScreen()
                selected == MainTab.TRANSACTIONS -> TransactionsTab()
                selected == MainTab.BILLS -> BillsTab()
                selected == MainTab.REPORTS -> ReportsScreen()
                else -> SettingsScreen()
            }
        }
    }
}
