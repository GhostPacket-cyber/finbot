package br.com.finbot.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.MainActivity
import br.com.finbot.backup.BackupCrypto
import br.com.finbot.domain.Money
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private val TABLE_LABELS = linkedMapOf(
    "transactions" to "Lançamentos",
    "categories" to "Categorias",
    "accounts" to "Contas (carteiras)",
    "bills" to "Contas a pagar",
    "bill_occurrences" to "Vencimentos",
    "reminders" to "Lembretes",
    "budgets" to "Orçamentos",
    "import_batches" to "Importações",
    "csv_presets" to "Modelos de CSV",
)

/** Ajustes › Backup e segurança (ETAPA 11). */
@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory)) {
    BackHandler(onBack = onBack)
    val security by viewModel.security.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val restore by viewModel.restore.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? MainActivity
    val snackbar = remember { SnackbarHostState() }

    var exportDialog by remember { mutableStateOf(false) }
    var wipeDialog by remember { mutableStateOf(false) }
    var replaceStep by remember { mutableStateOf(0) }
    var mergeConfirm by remember { mutableStateOf(false) }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.export(uri, context.contentResolver) else viewModel.cancelExport()
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.pickedRestoreFile(uri)
    }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        viewModel.messageShown()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Voltar") }
                Text("Backup e segurança", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            val s = security ?: return@Column

            Text("Segurança", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            ToggleLine(
                "Bloquear o app",
                "Pede a biometria ou o bloqueio de tela do aparelho ao abrir e ao voltar ao FinBot.",
                s.appLock,
            ) { on ->
                when {
                    !on -> viewModel.lockEnabled(false)
                    activity == null || !activity.canAuthenticate() ->
                        viewModel.say("Configure um bloqueio de tela (PIN, padrão, senha ou biometria) no Android primeiro.")
                    else -> activity.authenticate { viewModel.lockEnabled(true) }
                }
            }
            if (s.appLock) {
                Choice("Bloquear de novo após", listOf(0 to "voltar ao app", 1 to "1 minuto", 5 to "5 minutos", 15 to "15 minutos", 30 to "30 minutos"), s.lockTimeoutMinutes) { v ->
                    viewModel.setSecurity { it.copy(lockTimeoutMinutes = v) }
                }
            }
            ToggleLine(
                "Proteger a tela",
                "Impede capturas e gravação de tela e esconde o FinBot na lista de apps recentes.",
                s.secureScreen,
            ) { on -> viewModel.setSecurity { it.copy(secureScreen = on) } }

            HorizontalDivider()
            Text("Backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "Seus dados ficam só neste aparelho. Desinstalar o FinBot, limpar os dados do app ou perder o celular apaga " +
                        "tudo o que não foi exportado.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                s.lastExportAt?.let { "Último backup: ${it.atZone(ZoneId.systemDefault()).format(DATE_TIME)}" } ?: "Nenhum backup exportado ainda.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { exportDialog = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Exportar backup") }
            OutlinedButton(onClick = { openDocument.launch(arrayOf("*/*")) }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                Text("Importar backup")
            }
            Choice("Lembrar de exportar", listOf(0 to "nunca", 7 to "a cada 7 dias", 15 to "a cada 15 dias", 30 to "a cada 30 dias", 60 to "a cada 60 dias", 90 to "a cada 90 dias"), s.backupReminderDays) { v ->
                viewModel.setSecurity { it.copy(backupReminderDays = v) }
            }
            Text(
                "O backup (.finbot) é criptografado com a sua senha (AES-256-GCM). Ele leva lançamentos, categorias, contas, " +
                    "lembretes, orçamentos e importações. Não leva a chave da Gemini, os ajustes nem o histórico do chat.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("Apagar tudo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                "Apaga lançamentos, contas, chat, ajustes, a chave da Gemini e a chave de criptografia deste aparelho. Não dá para desfazer.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { wipeDialog = true },
                enabled = busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apagar todos os dados") }

            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(it)
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    if (exportDialog) {
        PasswordDialog(
            title = "Senha do backup",
            info = "Escolha uma senha com pelo menos ${BackupCrypto.MIN_PASSWORD} caracteres. Sem ela o arquivo não pode ser aberto, nem por nós.",
            confirmField = true,
            confirmLabel = "Escolher onde salvar",
            onDismiss = { exportDialog = false },
        ) { password, confirm ->
            val error = viewModel.prepareExport(password, confirm)
            if (error == null) {
                exportDialog = false
                createDocument.launch("finbot-${LocalDate.now()}.finbot")
            }
            error
        }
    }

    when (val r = restore) {
        is RestoreStep.NeedPassword -> PasswordDialog(
            title = "Senha do backup",
            info = "Digite a senha usada ao exportar.",
            confirmField = false,
            confirmLabel = "Abrir",
            onDismiss = viewModel::cancelRestore,
        ) { password, _ ->
            viewModel.openRestore(password, context.contentResolver)
            null
        }
        is RestoreStep.Ready -> if (replaceStep == 0 && !mergeConfirm) {
            AlertDialog(
                onDismissRequest = viewModel::cancelRestore,
                title = { Text("Backup conferido") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Criado em ${java.time.Instant.ofEpochMilli(r.manifest.createdAtEpochMs).atZone(ZoneId.systemDefault()).format(DATE_TIME)} (versão ${r.manifest.appVersion}).")
                        Text("Lançamentos: ${r.manifest.tables["transactions"]?.rows ?: 0} · Saldo: ${Money.format(r.manifest.balanceCents)}")
                        Text("Se mesclar: ${r.preview.totalAdded} novo(s), ${r.preview.totalIgnored} já existe(m) aqui.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { mergeConfirm = true }) { Text("Mesclar com os dados daqui") }
                        TextButton(onClick = { replaceStep = 1 }) { Text("Substituir tudo") }
                        TextButton(onClick = viewModel::cancelRestore) { Text("Cancelar") }
                    }
                },
            )
        }
        RestoreStep.Idle -> Unit
    }

    val ready = restore as? RestoreStep.Ready
    if (ready != null && mergeConfirm) {
        AlertDialog(
            onDismissRequest = { mergeConfirm = false },
            title = { Text("Mesclar backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Nada daqui é apagado ou sobrescrito; o que já existe é ignorado.", style = MaterialTheme.typography.bodySmall)
                    TABLE_LABELS.forEach { (table, label) ->
                        val add = ready.preview.added[table] ?: 0
                        val skip = ready.preview.ignored[table] ?: 0
                        if (add + skip > 0) Text("$label: +$add novo(s), $skip ignorado(s)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mergeConfirm = false; viewModel.merge() }) { Text("Mesclar") } },
            dismissButton = { TextButton(onClick = { mergeConfirm = false }) { Text("Voltar") } },
        )
    }
    if (ready != null && replaceStep == 1) {
        AlertDialog(
            onDismissRequest = { replaceStep = 0 },
            title = { Text("Substituir todos os dados?") },
            text = {
                Text(
                    "Todos os lançamentos, contas e lembretes deste aparelho serão trocados pelos do backup. " +
                        "Se quiser guardar os atuais, cancele e exporte um backup antes.",
                )
            },
            confirmButton = { TextButton(onClick = { replaceStep = 2 }) { Text("Continuar") } },
            dismissButton = { TextButton(onClick = { replaceStep = 0 }) { Text("Cancelar") } },
        )
    }
    if (ready != null && replaceStep == 2) {
        TypeToConfirmDialog(
            title = "Confirmação final",
            word = "SUBSTITUIR",
            action = "Substituir",
            onDismiss = { replaceStep = 0 },
        ) {
            replaceStep = 0
            viewModel.replace()
        }
    }
    if (wipeDialog) {
        TypeToConfirmDialog(
            title = "Apagar todos os dados?",
            word = "APAGAR",
            action = "Apagar tudo",
            onDismiss = { wipeDialog = false },
        ) {
            wipeDialog = false
            viewModel.wipeAll()
        }
    }
}

@Composable
private fun ToggleLine(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: "—"
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $current", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

/** [onSubmit] devolve uma mensagem de erro para mostrar, ou null. */
@Composable
private fun PasswordDialog(
    title: String,
    info: String,
    confirmField: Boolean,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> String?,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(128); error = null },
                    label = { Text("Senha") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = keyboard,
                )
                if (confirmField) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it.take(128); error = null },
                        label = { Text("Repita a senha") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = keyboard,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSubmit(password, confirm) }, enabled = password.isNotEmpty()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Confirmação forte: só habilita depois de digitar [word]. */
@Composable
private fun TypeToConfirmDialog(title: String, word: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Não dá para desfazer. Para confirmar, digite $word.")
                OutlinedTextField(value = typed, onValueChange = { typed = it.take(20) }, singleLine = true, label = { Text(word) })
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = typed.trim().equals(word, ignoreCase = false)) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
