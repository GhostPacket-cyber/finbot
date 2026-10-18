package br.com.finbot.ui.settings

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
import br.com.finbot.diagnostics.DeviceInfo
import br.com.finbot.diagnostics.StorageHealth
import br.com.finbot.security.SafeLog
import br.com.finbot.security.SecretKind
import br.com.finbot.security.SecretStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class SettingsUiState(
    val form: ConfigForm = ConfigForm(),
    val errors: ConfigErrors = ConfigErrors(),
    val config: AppConfig = AppConfig(),
    val geminiStatus: SecretStatus = SecretStatus.Missing,
    val storage: StorageHealth? = null,
    val device: DeviceInfo? = null,
    val saving: Boolean = false,
    val message: String? = null,
)

private data class Transient(
    val storage: StorageHealth? = null,
    val device: DeviceInfo? = null,
    val saving: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val form = MutableStateFlow(ConfigForm())
    private val errors = MutableStateFlow(ConfigErrors())
    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<SettingsUiState> = combine(
        form,
        errors,
        container.configRepository.config,
        container.secretStore.statuses,
        transient,
    ) { f, e, config, secrets, t ->
        SettingsUiState(
            form = f,
            errors = e,
            config = config,
            geminiStatus = secrets[SecretKind.GEMINI_API_KEY] ?: SecretStatus.Missing,
            storage = t.storage,
            device = t.device,
            saving = t.saving,
            message = t.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            val saved = container.configRepository.config.first()
            form.update { it.copy(displayName = saved.displayName, timezone = saved.timezone, geminiModel = saved.geminiModel) }
        }
    }

    fun onFormChange(transform: (ConfigForm) -> ConfigForm) {
        form.update(transform)
    }

    fun save() {
        if (transient.value.saving) return
        val current = form.value
        val validation = ConfigValidator.validate(current)
        errors.value = validation
        if (validation.hasErrors) {
            showMessage("Corrija os campos destacados.")
            return
        }
        viewModelScope.launch {
            transient.update { it.copy(saving = true) }
            try {
                if (current.geminiKey.isNotBlank()) {
                    container.secretStore.put(SecretKind.GEMINI_API_KEY, current.geminiKey)
                }
                container.configRepository.saveProfile(current.displayName, current.timezone)
                container.configRepository.setGeminiModel(current.geminiModel)
                // A chave não permanece na memória da UI após salva.
                form.update { it.copy(geminiKey = "") }
                SafeLog.i("Ajustes salvos")
                showMessage("Ajustes salvos.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha ao salvar ajustes", e)
                showMessage("Não foi possível salvar. Tente novamente.")
            } finally {
                transient.update { it.copy(saving = false) }
            }
        }
    }

    fun removeGeminiKey() {
        viewModelScope.launch {
            container.secretStore.remove(SecretKind.GEMINI_API_KEY)
            SafeLog.i("Chave Gemini removida")
            showMessage("Chave Gemini removida.")
        }
    }

    fun setAiConsent(granted: Boolean) {
        viewModelScope.launch {
            container.configRepository.setAiConsent(granted, Instant.now(container.clock))
            SafeLog.i("Consentimento de IA alterado: $granted")
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val (storage, device) = withContext(Dispatchers.IO) {
                container.diagnostics.checkStorage() to container.diagnostics.deviceInfo()
            }
            transient.update { it.copy(storage = storage, device = device) }
        }
    }

    fun messageShown() {
        transient.update { it.copy(message = null) }
    }

    private fun showMessage(text: String) {
        transient.update { it.copy(message = text) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel((this[APPLICATION_KEY] as FinBotApp).container) }
        }
    }
}
