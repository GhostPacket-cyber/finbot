package br.com.finbot.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.BuildConfig
import br.com.finbot.config.ConfigErrors
import br.com.finbot.config.ConfigValidator
import br.com.finbot.security.SecretStatus
import br.com.finbot.ui.formatInstant
import br.com.finbot.ui.zoneOrDefault
import java.time.ZoneId

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showBank by rememberSaveable { mutableStateOf(false) }
    if (showBank) {
        BankNotificationsScreen(onBack = { showBank = false })
        return
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshDiagnostics()
        onPauseOrDispose { }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Ajustes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

            SectionCard("Perfil") {
                ProfileFields(
                    displayName = state.form.displayName,
                    onDisplayNameChange = { v -> viewModel.onFormChange { it.copy(displayName = v) } },
                    timezone = state.form.timezone,
                    onTimezoneChange = { v -> viewModel.onFormChange { it.copy(timezone = v) } },
                    errors = state.errors,
                )
                TextButton(onClick = { viewModel.onFormChange { it.copy(timezone = ZoneId.systemDefault().id) } }) {
                    Text("Usar fuso do aparelho")
                }
            }

            AiCard(state, viewModel)

            SectionCard("Notificações de banco") {
                Text(
                    "Sugere lançamentos a partir das notificações de compra e Pix dos apps de banco que você escolher. Desligado por padrão.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { showBank = true }) { Text("Configurar notificações de banco") }
            }

            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.saving) "Salvando…" else "Salvar ajustes")
            }

            DiagnosticsCard(state, onRefresh = viewModel::refreshDiagnostics, onOpenBatterySettings = {
                try {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            })

            SectionCard("Sobre") {
                Text("FinBot ${BuildConfig.VERSION_NAME}")
                Text(
                    "Os dados financeiros ficam apenas neste aparelho. Nada é enviado a servidores do FinBot " +
                        "(não existem). Somente se você ativar a IA, as mensagens do assistente são enviadas ao Google Gemini.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Campos de nome e fuso, compartilhados entre onboarding e Ajustes. */
@Composable
fun ProfileFields(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    timezone: String,
    onTimezoneChange: (String) -> Unit,
    errors: ConfigErrors,
) {
    OutlinedTextField(
        value = displayName,
        onValueChange = { onDisplayNameChange(it.take(ConfigValidator.MAX_DISPLAY_NAME)) },
        label = { Text("Como quer ser chamado? (opcional)") },
        singleLine = true,
        isError = errors.displayName != null,
        supportingText = errors.displayName?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = timezone,
        onValueChange = onTimezoneChange,
        label = { Text("Fuso horário (Área/Cidade)") },
        singleLine = true,
        isError = errors.timezone != null,
        supportingText = errors.timezone?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun AiCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    val consent = state.config.aiConsentGranted

    SectionCard("Assistente com IA (Gemini)") {
        Text(
            "Opcional. Com a IA, o chat entende frases livres, vários gastos numa mensagem e áudio. " +
                "Sem IA, ele entende comandos simples. Tudo continua exigindo sua confirmação.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (consent) "Envio ao Gemini: ligado" else "Envio ao Gemini: desligado", modifier = Modifier.weight(1f))
            Switch(
                checked = consent,
                onCheckedChange = { checked -> if (checked) showConsent = true else viewModel.setAiConsent(false) },
                modifier = Modifier.semantics { contentDescription = "Permitir envio de mensagens ao Gemini" },
            )
        }
        state.config.aiConsentAt?.let {
            Text(
                "Autorizado em ${formatInstant(it, zoneOrDefault(state.config.timezone))}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!consent && state.geminiStatus is SecretStatus.Configured) {
            Text(
                "A chave está salva, mas a IA só funciona depois que você ligar o envio acima.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        val statusText = when (val s = state.geminiStatus) {
            SecretStatus.Missing -> "Não configurada"
            is SecretStatus.Configured -> "Configurada (${s.hint})"
            SecretStatus.Unreadable -> "Ilegível — informe uma nova chave"
        }
        Text("Chave da Gemini API: $statusText", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.form.geminiKey,
            onValueChange = { v -> viewModel.onFormChange { it.copy(geminiKey = v) } },
            label = { Text(if (state.geminiStatus is SecretStatus.Configured) "Substituir chave" else "Chave da Gemini API") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = state.errors.geminiKey != null,
            supportingText = state.errors.geminiKey?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "A chave é cifrada com o Android Keystore e nunca é exibida por completo. " +
                "Chaves guardadas no aparelho não são invioláveis.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = state.form.geminiModel,
            onValueChange = { v -> viewModel.onFormChange { it.copy(geminiModel = v.trim().lowercase()) } },
            label = { Text("Modelo") },
            singleLine = true,
            isError = state.errors.geminiModel != null,
            supportingText = { Text(state.errors.geminiModel ?: "Padrão: ${ConfigValidator.DEFAULT_GEMINI_MODEL}") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.geminiStatus !is SecretStatus.Missing) {
            TextButton(onClick = { confirmRemoval = true }) { Text("Remover chave") }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Enviar mensagens ao Gemini?") },
            text = {
                Text(
                    "Os textos e áudios que você mandar ao assistente serão transmitidos à Gemini API do Google " +
                        "para interpretação e podem conter informações financeiras pessoais. No nível gratuito, o Google " +
                        "pode reter e usar o conteúdo para melhorar seus produtos, inclusive com revisão humana, conforme " +
                        "os termos vigentes em ai.google.dev. Seu histórico financeiro não é enviado, e você pode revogar " +
                        "a qualquer momento.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsent = false
                    viewModel.setAiConsent(true)
                }) { Text("Concordo") }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Cancelar") } },
        )
    }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("Remover chave?") },
            text = { Text("A chave da Gemini API será apagada deste aparelho.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    viewModel.removeGeminiKey()
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DiagnosticsCard(state: SettingsUiState, onRefresh: () -> Unit, onOpenBatterySettings: () -> Unit) {
    val context = LocalContext.current
    SectionCard("Diagnóstico do aparelho") {
        val storage = state.storage
        if (storage == null) {
            Text("Verificando armazenamento…")
        } else {
            Text(
                "Armazenamento: ${storage.message} Livre: ${Formatter.formatShortFileSize(context, storage.freeBytes)}",
                color = if (storage.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        state.device?.let { d ->
            Text("Android ${d.androidVersion} (API ${d.sdkInt}) — ${d.manufacturer} ${d.model}")
            Text(
                "Otimização de bateria: " +
                    if (d.ignoringBatteryOptimizations) "sem restrição para o FinBot" else "ativa (lembretes podem atrasar)",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenBatterySettings) { Text("Ajustes de bateria") }
            TextButton(onClick = onRefresh) { Text("Atualizar") }
        }
    }
}
