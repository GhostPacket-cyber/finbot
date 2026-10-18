package br.com.finbot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import br.com.finbot.ui.settings.SettingsScreen

enum class MainTab(val label: String, val icon: Int, val comingInStage: String?) {
    CHAT("Chat", R.drawable.ic_tab_chat, "ETAPA 03"),
    TRANSACTIONS("Lançamentos", R.drawable.ic_tab_list, "ETAPA 05"),
    BILLS("Contas", R.drawable.ic_tab_bills, "ETAPA 06"),
    REPORTS("Relatórios", R.drawable.ic_tab_reports, "ETAPA 07"),
    SETTINGS("Ajustes", R.drawable.ic_tab_settings, null),
}

@Composable
fun FinBotRoot(viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val loaded = config
    if (loaded == null) {
        Box(Modifier.fillMaxSize())
    } else if (!loaded.onboardingCompleted) {
        val errors by viewModel.onboardingErrors.collectAsStateWithLifecycle()
        OnboardingScreen(errors = errors, onComplete = viewModel::completeOnboarding)
    } else {
        MainScaffold()
    }
}

@Composable
private fun MainScaffold() {
    var selected by rememberSaveable { mutableStateOf(MainTab.CHAT) }
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val stage = selected.comingInStage) {
                null -> SettingsScreen()
                else -> ComingSoonScreen(title = selected.label, stage = stage)
            }
        }
    }
}
