package br.com.finbot.ui.bills

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.data.ShortMonthPolicy
import br.com.finbot.data.dao.OccurrenceView
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.entity.CategoryEntity
import br.com.finbot.data.entity.ReminderEntity
import br.com.finbot.domain.Money
import br.com.finbot.scheduling.ReminderOffsets
import br.com.finbot.scheduling.ReminderRepeat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

@Composable
fun BillsTab(viewModel: BillsViewModel = viewModel(factory = BillsViewModel.Factory)) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val paid by viewModel.paidRecently.collectAsStateWithLifecycle()
    val reminders by viewModel.reminderList.collectAsStateWithLifecycle()
    val zone by viewModel.userZone.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var confirmDeleteReminder by remember { mutableStateOf<ReminderEntity?>(null) }

    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        if (e.askNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val result = snackbar.showSnackbar(
            e.message,
            actionLabel = if (e.undoPaidOccurrenceId != null) "Desfazer" else null,
            duration = if (e.undoPaidOccurrenceId != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        viewModel.eventShown() // só depois de exibir (limpar antes cancelaria este efeito)
        if (result == SnackbarResult.ActionPerformed && e.undoPaidOccurrenceId != null) viewModel.undoPay(e.undoPaidOccurrenceId)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text(
                    "Contas e lembretes",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp).semantics { heading() },
                )
            }
            item { Section("A pagar") }
            val list = pending
            if (list != null && list.isEmpty()) {
                item { Hint("Nenhuma conta a pagar nos próximos 40 dias. Toque em + Conta para cadastrar.") }
            }
            items(list.orEmpty(), key = { "p${it.occurrenceId}" }) { o ->
                PendingRow(o, zone, onPay = { viewModel.openPay(o) }, onEdit = { viewModel.openEditBill(o.billId) })
                HorizontalDivider()
            }
            if (paid.isNotEmpty()) {
                item { Section("Pagas nos últimos 30 dias") }
                items(paid, key = { "d${it.occurrenceId}" }) { o ->
                    PaidRow(o, zone, onUndo = { viewModel.undoPay(o.occurrenceId) })
                    HorizontalDivider()
                }
            }
            item { Section("Lembretes") }
            if (reminders.isEmpty()) item { Hint("Nenhum lembrete. Toque em + Lembrete para criar.") }
            items(reminders, key = { "r${it.id}" }) { r ->
                ReminderRow(
                    r, zone,
                    onToggle = { viewModel.setReminderEnabled(r.id, it) },
                    onDelete = { confirmDeleteReminder = r },
                )
                HorizontalDivider()
            }
            item {
                Hint(
                    "Os avisos chegam por notificação, conferidos a cada ~30 min e ao abrir o app. " +
                        "Com a economia de bateria do aparelho, podem atrasar.",
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = viewModel::openNewReminder) { Text("+ Lembrete") }
            Button(onClick = viewModel::openNewBill) { Text("+ Conta") }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    val billForm by viewModel.billForm.collectAsStateWithLifecycle()
    val reminderForm by viewModel.reminderForm.collectAsStateWithLifecycle()
    val pay by viewModel.pay.collectAsStateWithLifecycle()
    val categories by viewModel.expenseCategories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    billForm?.let { BillFormDialog(it, categories, viewModel) }
    reminderForm?.let { ReminderFormDialog(it, viewModel) }
    pay?.let { PayDialog(it, accounts.filter { a -> !a.archived }, viewModel) }
    confirmDeleteReminder?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDeleteReminder = null },
            title = { Text("Excluir lembrete?") },
            text = { Text("\"${r.title}\" será removido.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteReminder(r.id); confirmDeleteReminder = null }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { confirmDeleteReminder = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}

/** "Vence hoje", "Vence em 3 dias", "Atrasada há 2 dias". */
fun dueLabel(due: java.time.LocalDate, today: java.time.LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, due)
    return when {
        days > 1 -> "Vence em $days dias (${due.format(DATE)})"
        days == 1L -> "Vence amanhã"
        days == 0L -> "Vence hoje"
        days == -1L -> "Atrasada há 1 dia"
        else -> "Atrasada há ${-days} dias"
    }
}

@Composable
private fun PendingRow(o: OccurrenceView, zone: ZoneId, onPay: () -> Unit, onEdit: () -> Unit) {
    val today = Instant.now().atZone(zone).toLocalDate()
    val label = dueLabel(o.dueDate, today)
    val overdue = o.dueDate.isBefore(today)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "${o.title}, ${Money.format(o.amountCents)}, $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(o.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                "$label · ${if (o.recurrenceRule != null) "mensal" else "única"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Money.format(o.amountCents), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
        Button(onClick = onPay) { Text("Pagar") }
    }
}

@Composable
private fun PaidRow(o: OccurrenceView, zone: ZoneId, onUndo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(o.title)
            Text(
                "Paga em ${o.paidAt?.atZone(zone)?.toLocalDate()?.format(DATE) ?: "—"} · vencimento ${o.dueDate.format(DATE)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Money.format(o.amountCents), modifier = Modifier.padding(end = 8.dp))
        TextButton(onClick = onUndo) { Text("Desfazer") }
    }
}

@Composable
private fun ReminderRow(r: ReminderEntity, zone: ZoneId, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val repeat = runCatching { ReminderRepeat.decode(r.recurrenceRule).label }.getOrDefault("—")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.title)
            Text(
                (if (r.enabled) "Próximo: ${r.scheduledAt.atZone(zone).format(DATE_TIME)}" else "Desativado") + " · $repeat",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = r.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = "Ativar lembrete ${r.title}" },
        )
        TextButton(onClick = onDelete) { Text("Excluir") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillFormDialog(f: BillFormState, categories: List<CategoryEntity>, vm: BillsViewModel) {
    val editing = f.billId != null
    var confirmDeactivate by rememberSaveable { mutableStateOf(false) }
    val dueDay = BillsViewModel.parseDate(f.firstDue)?.dayOfMonth ?: 0
    AlertDialog(
        onDismissRequest = vm::closeBillForm,
        title = { Text(if (editing) "Editar conta" else "Nova conta a pagar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.title, { v -> vm.updateBillForm { it.copy(title = v.take(80)) } }, label = { Text("Título (ex.: Luz)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    f.amount, { v -> vm.updateBillForm { it.copy(amount = v.take(20)) } },
                    label = { Text("Valor (R$)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    f.firstDue, { v -> vm.updateBillForm { it.copy(firstDue = v.take(10)) } },
                    label = { Text(if (editing) "Primeiro vencimento (fixo)" else "Vencimento (dd/mm/aaaa)") }, singleLine = true,
                    enabled = !editing, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Text("Repetição", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !f.monthly, onClick = { if (!editing) vm.updateBillForm { it.copy(monthly = false) } }, enabled = !editing, label = { Text("Única") })
                    FilterChip(selected = f.monthly, onClick = { if (!editing) vm.updateBillForm { it.copy(monthly = true) } }, enabled = !editing, label = { Text("Todo mês") })
                }
                if (f.monthly && dueDay >= 29) {
                    Text("Em meses sem o dia $dueDay", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(f.policy == ShortMonthPolicy.LAST_DAY_OF_MONTH, { vm.updateBillForm { it.copy(policy = ShortMonthPolicy.LAST_DAY_OF_MONTH) } }, label = { Text("Último dia do mês") })
                        FilterChip(f.policy == ShortMonthPolicy.FIRST_DAY_OF_NEXT_MONTH, { vm.updateBillForm { it.copy(policy = ShortMonthPolicy.FIRST_DAY_OF_NEXT_MONTH) } }, label = { Text("Dia 1º do mês seguinte") })
                    }
                }
                Text("Avisar (às 9h)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReminderOffsets.CHOICES.forEach { o ->
                        FilterChip(
                            selected = o in f.offsets,
                            onClick = { vm.updateBillForm { it.copy(offsets = if (o in it.offsets) it.offsets - o else it.offsets + o) } },
                            label = { Text(ReminderOffsets.label(o)) },
                        )
                    }
                }
                Text("Categoria", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        FilterChip(c.id == f.categoryId, { vm.updateBillForm { it.copy(categoryId = if (it.categoryId == c.id) null else c.id) } }, label = { Text(c.name) })
                    }
                }
                f.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (editing) TextButton(onClick = { confirmDeactivate = true }) { Text("Desativar conta", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = vm::saveBill) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = vm::closeBillForm) { Text("Cancelar") } },
    )
    if (confirmDeactivate && f.billId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeactivate = false },
            title = { Text("Desativar \"${f.title}\"?") },
            text = { Text("Ela sai da lista a pagar e para de gerar avisos. Pagamentos já registrados continuam nos lançamentos.") },
            confirmButton = { TextButton(onClick = { confirmDeactivate = false; vm.deactivateBill(f.billId) }) { Text("Desativar") } },
            dismissButton = { TextButton(onClick = { confirmDeactivate = false }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderFormDialog(f: ReminderFormState, vm: BillsViewModel) {
    AlertDialog(
        onDismissRequest = vm::closeReminderForm,
        title = { Text("Novo lembrete") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.title, { v -> vm.updateReminderForm { it.copy(title = v.take(80)) } }, label = { Text("Lembrar de…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(f.date, { v -> vm.updateReminderForm { it.copy(date = v.take(10)) } }, label = { Text("Data") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(f.time, { v -> vm.updateReminderForm { it.copy(time = v.take(5)) } }, label = { Text("Hora") }, singleLine = true, modifier = Modifier.weight(0.6f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(REPEAT_ONCE to "Uma vez", REPEAT_DAILY to "Todo dia", REPEAT_WEEKLY to "Toda semana", REPEAT_MONTHLY to "Todo mês").forEach { (code, label) ->
                        FilterChip(f.repeat == code, { vm.updateReminderForm { it.copy(repeat = code) } }, label = { Text(label) })
                    }
                }
                f.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = vm::saveReminder) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = vm::closeReminderForm) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayDialog(p: PayState, accounts: List<AccountEntity>, vm: BillsViewModel) {
    AlertDialog(
        onDismissRequest = vm::closePay,
        title = { Text("Pagar ${p.occurrence.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Valor: ${Money.format(p.occurrence.amountCents)} · vencimento ${p.occurrence.dueDate.format(DATE)}")
                Text("Será criado um lançamento de despesa (uma única vez).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(p.date, { v -> vm.updatePay { it.copy(date = v.take(10)) } }, label = { Text("Data do pagamento") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (accounts.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { a -> FilterChip(a.id == p.accountId, { vm.updatePay { it.copy(accountId = a.id) } }, label = { Text(a.name) }) }
                    }
                }
                p.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = vm::confirmPay) { Text("Confirmar pagamento") } },
        dismissButton = { TextButton(onClick = vm::closePay) { Text("Cancelar") } },
    )
}
