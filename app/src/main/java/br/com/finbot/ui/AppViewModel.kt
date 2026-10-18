package br.com.finbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.AppContainer
import br.com.finbot.FinBotApp
import br.com.finbot.config.AppConfig
import br.com.finbot.config.ConfigErrors
import br.com.finbot.config.ConfigForm
import br.com.finbot.config.ConfigValidator
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Raiz do app: decide entre onboarding e telas principais e conclui o onboarding. */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    /** null enquanto a configuração ainda não foi lida (evita piscar o onboarding). */
    val config: StateFlow<AppConfig?> = container.configRepository.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _onboardingErrors = MutableStateFlow(ConfigErrors())
    val onboardingErrors: StateFlow<ConfigErrors> = _onboardingErrors.asStateFlow()

    fun completeOnboarding(displayName: String, timezone: String) {
        val errors = ConfigValidator.validate(ConfigForm(displayName = displayName, timezone = timezone))
        _onboardingErrors.value = errors
        if (errors.hasErrors) return
        viewModelScope.launch {
            container.configRepository.completeOnboarding(displayName, timezone)
            SafeLog.i("Onboarding concluído")
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel((this[APPLICATION_KEY] as FinBotApp).container) }
        }
    }
}
