package br.com.finbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.finbot.AppContainer
import br.com.finbot.FinBotApp
import br.com.finbot.config.AdminConfig
import br.com.finbot.config.ConfigErrors
import br.com.finbot.config.ConfigForm
import br.com.finbot.config.ConfigValidator
import br.com.finbot.security.SafeLog
import br.com.finbot.security.SecretKind
import br.com.finbot.security.SecretStatus
import br.com.finbot.service.BotState
import br.com.finbot.service.DeviceInfo
import br.com.finbot.service.StorageHealth
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

data class AdminUiState(
    val form: ConfigForm = ConfigForm(),
    val errors: ConfigErrors = ConfigErrors(),
    val config: AdminConfig = AdminConfig(),
    val secrets: Map<SecretKind, SecretStatus> = emptyMap(),
    val botState: BotState = BotState(),
    val storage: StorageHealth? = null,
    val device: DeviceInfo? = null,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val telegramStatus: SecretStatus get() = secrets[SecretKind.TELEGRAM_BOT_TOKEN] ?: SecretStatus.Missing
    val geminiStatus: SecretStatus get() = secrets[SecretKind.GEMINI_API_KEY] ?: SecretStatus.Missing

    /** Requisitos mínimos para iniciar o bot: token legível e ao menos um usuário salvo. */
    val canStart: Boolean
        get() = telegramStatus is SecretStatus.Configured && config.authorizedUserIds.isNotEmpty()
}

private data class Persisted(
    val config: AdminConfig,
    val secrets: Map<SecretKind, SecretStatus>,
    val botState: BotState,
)

private data class Transient(
    val storage: StorageHealth? = null,
    val device: DeviceInfo? = null,
    val saving: Boolean = false,
    val message: String? = null,
)

class AdminViewModel(private val container: AppContainer) : ViewModel() {

    private val form = MutableStateFlow(ConfigForm())
    private val errors = MutableStateFlow(ConfigErrors())
    private val transient = MutableStateFlow(Transient())

    private val persisted = combine(
        container.configRepository.config,
        container.secretStore.statuses,
        container.botStateRepository.state,
        ::Persisted,
    )

    val uiState: StateFlow<AdminUiState> = combine(form, errors, persisted, transient) { f, e, p, t ->
        AdminUiState(
            form = f,
            errors = e,
            config = p.config,
            secrets = p.secrets,
            botState = p.botState,
            storage = t.storage,
            device = t.device,
            saving = t.saving,
            message = t.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdminUiState())

    init {
        viewModelScope.launch {
            val saved = container.configRepository.config.first()
            form.update {
                it.copy(
                    user1Id = saved.authorizedUserIds.getOrNull(0)?.toString().orEmpty(),
                    user2Id = saved.authorizedUserIds.getOrNull(1)?.toString().orEmpty(),
                    timezone = saved.timezone,
                )
            }
        }
    }

    fun onFormChange(transform: (ConfigForm) -> ConfigForm) {
        form.update(transform)
    }

    fun save() {
        if (transient.value.saving) return
        viewModelScope.launch {
            val hasToken = container.secretStore.statuses.first()[SecretKind.TELEGRAM_BOT_TOKEN] is SecretStatus.Configured
            val current = form.value
            val validation = ConfigValidator.validate(current, hasToken)
            errors.value = validation
            if (validation.hasErrors) {
                showMessage("Corrija os campos destacados.")
                return@launch
            }
            transient.update { it.copy(saving = true) }
            try {
                if (current.telegramToken.isNotBlank()) {
                    container.secretStore.put(SecretKind.TELEGRAM_BOT_TOKEN, current.telegramToken)
                }
                if (current.geminiKey.isNotBlank()) {
                    container.secretStore.put(SecretKind.GEMINI_API_KEY, current.geminiKey)
                }
                container.configRepository.saveUsersAndTimezone(
                    ConfigValidator.authorizedIds(current),
                    current.timezone.trim(),
                )
                // Segredos não permanecem na memória da UI após salvos.
                form.update { it.copy(telegramToken = "", geminiKey = "") }
                SafeLog.i("Configuração salva")
                showMessage("Configuração salva.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("Falha ao salvar configuração", e)
                showMessage("Não foi possível salvar. Tente novamente.")
            } finally {
                transient.update { it.copy(saving = false) }
            }
        }
    }

    fun removeSecret(kind: SecretKind) {
        viewModelScope.launch {
            container.secretStore.remove(kind)
            SafeLog.i("Segredo removido: ${kind.name}")
            showMessage(
                when (kind) {
                    SecretKind.TELEGRAM_BOT_TOKEN -> "Token do Telegram removido."
                    SecretKind.GEMINI_API_KEY -> "Chave Gemini removida."
                },
            )
        }
    }

    fun setAiConsent(granted: Boolean) {
        viewModelScope.launch {
            container.configRepository.setAiConsent(granted, Instant.now(container.clock))
            SafeLog.i("Consentimento de IA alterado: $granted")
        }
    }

    fun start() {
        viewModelScope.launch {
            if (!uiState.value.canStart) {
                showMessage("Salve o token do bot e ao menos um usuário antes de iniciar.")
                return@launch
            }
            container.botController.start()
        }
    }

    fun stop() {
        viewModelScope.launch { container.botController.stop() }
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
            initializer { AdminViewModel((this[APPLICATION_KEY] as FinBotApp).container) }
        }
    }
}
