package br.com.finbot.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.security.SecretKind
import br.com.finbot.security.SecretStatus
import br.com.finbot.service.BotStatus
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", PT_BR)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(viewModel: AdminViewModel = viewModel(factory = AdminViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

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

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshDiagnostics()
        viewModel.start()
    }

    fun onStartClick() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            state.device?.notificationsAllowed == false
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("FinBot — Administração") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(state, onStart = ::onStartClick, onStop = viewModel::stop)
            CredentialsCard(state, viewModel)
            UsersCard(state, viewModel)
            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.saving) "Salvando…" else "Salvar configuração") }
            AiConsentCard(state, viewModel)
            DiagnosticsCard(state, onRefresh = viewModel::refreshDiagnostics, onOpenBatterySettings = {
                try {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            })
        }
    }
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
private fun StatusCard(state: AdminUiState, onStart: () -> Unit, onStop: () -> Unit) {
    val bot = state.botState
    val zone = zoneOrDefault(state.config.timezone)
    val color = when (bot.status) {
        BotStatus.CONECTADO -> MaterialTheme.colorScheme.primary
        BotStatus.ERRO -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SectionCard("Status do bot") {
        Text(
            "Estado: ${bot.status.label}",
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text("Última atividade: ${formatInstant(bot.lastActivityAt, zone)}")
        Text("Falhas registradas: ${bot.failureCount}")
        bot.lastError?.let { Text("Último erro: $it", color = MaterialTheme.colorScheme.error) }
        if (!state.canStart) {
            Text(
                "Para iniciar, salve o token do bot e ao menos um usuário.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val stopped = bot.status == BotStatus.DESLIGADO || bot.status == BotStatus.ERRO
            Button(onClick = onStart, enabled = state.canStart && stopped) {
                Text("Iniciar")
            }
            OutlinedButton(onClick = onStop, enabled = bot.status != BotStatus.DESLIGADO) { Text("Parar") }
        }
    }
}

@Composable
private fun CredentialsCard(state: AdminUiState, viewModel: AdminViewModel) {
    SectionCard("Credenciais") {
        Text(
            "Os valores são cifrados com o Android Keystore e nunca são exibidos por completo. " +
                "Chaves guardadas no aparelho não são invioláveis: um aparelho comprometido pode expô-las.",
            style = MaterialTheme.typography.bodySmall,
        )
        SecretField(
            label = "Token do bot Telegram",
            status = state.telegramStatus,
            value = state.form.telegramToken,
            error = state.errors.telegramToken,
            onValueChange = { v -> viewModel.onFormChange { it.copy(telegramToken = v) } },
            onRemove = { viewModel.removeSecret(SecretKind.TELEGRAM_BOT_TOKEN) },
        )
        SecretField(
            label = "Chave da Gemini API (opcional)",
            status = state.geminiStatus,
            value = state.form.geminiKey,
            error = state.errors.geminiKey,
            onValueChange = { v -> viewModel.onFormChange { it.copy(geminiKey = v) } },
            onRemove = { viewModel.removeSecret(SecretKind.GEMINI_API_KEY) },
        )
    }
}

@Composable
private fun SecretField(
    label: String,
    status: SecretStatus,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    val statusText = when (status) {
        SecretStatus.Missing -> "Não configurado"
        is SecretStatus.Configured -> "Configurado (${status.hint})"
        SecretStatus.Unreadable -> "Ilegível — informe um novo valor"
    }
    Text("$label: $statusText", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (status is SecretStatus.Configured) "Substituir $label" else label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    if (status !is SecretStatus.Missing) {
        TextButton(onClick = { confirmRemoval = true }) { Text("Remover") }
    }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("Remover credencial?") },
            text = { Text("$label será apagado deste aparelho.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    onRemove()
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun UsersCard(state: AdminUiState, viewModel: AdminViewModel) {
    SectionCard("Usuários autorizados e fuso") {
        Text(
            "Somente estes IDs numéricos do Telegram poderão usar o bot (máximo de dois). " +
                "Nomes de usuário (@) não são aceitos porque podem ser trocados.",
            style = MaterialTheme.typography.bodySmall,
        )
        NumberField("ID do usuário 1", state.form.user1Id, state.errors.user1Id) { v ->
            viewModel.onFormChange { it.copy(user1Id = v) }
        }
        NumberField("ID do usuário 2 (opcional)", state.form.user2Id, state.errors.user2Id) { v ->
            viewModel.onFormChange { it.copy(user2Id = v) }
        }
        OutlinedTextField(
            value = state.form.timezone,
            onValueChange = { v -> viewModel.onFormChange { it.copy(timezone = v) } },
            label = { Text("Fuso horário (Área/Cidade)") },
            singleLine = true,
            isError = state.errors.timezone != null,
            supportingText = state.errors.timezone?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { viewModel.onFormChange { it.copy(timezone = ZoneId.systemDefault().id) } }) {
            Text("Usar fuso do aparelho")
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, error: String?, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter(Char::isDigit).take(15)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AiConsentCard(state: AdminUiState, viewModel: AdminViewModel) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val consent = state.config.aiConsentGranted
    SectionCard("Interpretação por IA (Gemini)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (consent) "Consentimento concedido" else "Desativada",
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = consent,
                onCheckedChange = { checked -> if (checked) showDialog = true else viewModel.setAiConsent(false) },
                modifier = Modifier.semantics { contentDescription = "Permitir envio de mensagens ao Gemini" },
            )
        }
        state.config.aiConsentAt?.let {
            Text("Concedido em ${formatInstant(it, zoneOrDefault(state.config.timezone))}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Sem consentimento, o bot aceita apenas comandos estruturados (ex.: /gasto 45 almoço).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Enviar mensagens ao Gemini?") },
            text = {
                Text(
                    "Textos e áudios enviados ao bot serão transmitidos à Gemini API do Google para interpretação. " +
                        "Esse conteúdo pode incluir informações financeiras pessoais. No nível gratuito, o Google pode " +
                        "reter e usar o conteúdo para melhorar seus produtos, inclusive com revisão humana, conforme os " +
                        "termos vigentes em ai.google.dev. O banco de dados continua apenas neste aparelho, e você pode " +
                        "revogar este consentimento a qualquer momento.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    viewModel.setAiConsent(true)
                }) { Text("Concordo") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DiagnosticsCard(state: AdminUiState, onRefresh: () -> Unit, onOpenBatterySettings: () -> Unit) {
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
            Text("Notificações: ${if (d.notificationsAllowed) "permitidas" else "bloqueadas"}")
            Text(
                "Otimização de bateria: " +
                    if (d.ignoringBatteryOptimizations) "sem restrição para o FinBot" else "ativa (o Android pode pausar o bot)",
            )
        }
        Text(
            "O bot roda neste telefone e não tem garantia de funcionar 24 horas: o Android, o fabricante, " +
                "a falta de rede ou um reinício podem interrompê-lo.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenBatterySettings) { Text("Ajustes de bateria") }
            TextButton(onClick = onRefresh) { Text("Atualizar") }
        }
    }
}

private fun zoneOrDefault(id: String): ZoneId =
    try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        ZoneId.systemDefault()
    }

private fun formatInstant(instant: Instant?, zone: ZoneId): String =
    instant?.atZone(zone)?.format(DATE_TIME) ?: "nunca"
