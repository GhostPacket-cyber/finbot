package br.com.finbot.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.data.AccountType
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.dao.CategoryWithUsage
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.repository.CategoryRepository

/** Diálogo aberto na tela (um por vez). */
private sealed interface ManageDialog {
    data class NewCategory(val kind: TransactionKind) : ManageDialog
    data class RenameCategory(val id: Long, val current: String) : ManageDialog
    data class Merge(val source: CategoryWithUsage) : ManageDialog
    data object NewAccount : ManageDialog
    data class RenameAccount(val id: Long, val current: String) : ManageDialog
}

@Composable
fun ManageScreen(onBack: () -> Unit, viewModel: ManageViewModel = viewModel(factory = ManageViewModel.Factory)) {
    val categories by viewModel.categoryList.collectAsStateWithLifecycle()
    val accounts by viewModel.accountList.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<ManageDialog?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown() // só depois: limpar antes cancelaria este efeito
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← Voltar") }
                    Text("Categorias e contas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                }
            }
            listOf(TransactionKind.EXPENSE to "Categorias de despesa", TransactionKind.INCOME to "Categorias de receita").forEach { (kind, title) ->
                item {
                    SectionHeader(title, actionLabel = "+ Nova") { dialog = ManageDialog.NewCategory(kind) }
                }
                items(categories.filter { it.category.kind == kind }, key = { "c${it.category.id}" }) { c ->
                    CategoryRow(
                        item = c,
                        onRename = { dialog = ManageDialog.RenameCategory(c.category.id, c.category.name) },
                        onMerge = { dialog = ManageDialog.Merge(c) },
                        onToggleArchive = { viewModel.setCategoryArchived(c.category.id, !c.category.archived) },
                    )
                    HorizontalDivider()
                }
            }
            item { SectionHeader("Contas", actionLabel = "+ Nova") { dialog = ManageDialog.NewAccount } }
            items(accounts, key = { "a${it.id}" }) { a ->
                AccountRow(
                    account = a,
                    onRename = { dialog = ManageDialog.RenameAccount(a.id, a.name) },
                    onToggleArchive = { viewModel.setAccountArchived(a.id, !a.archived) },
                )
                HorizontalDivider()
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    when (val d = dialog) {
        is ManageDialog.NewCategory -> NameDialog(
            title = if (d.kind == TransactionKind.EXPENSE) "Nova categoria de despesa" else "Nova categoria de receita",
            initial = "",
            onDismiss = { dialog = null },
            onConfirm = { viewModel.createCategory(it, d.kind); dialog = null },
        )
        is ManageDialog.RenameCategory -> NameDialog(
            title = "Renomear categoria",
            initial = d.current,
            onDismiss = { dialog = null },
            onConfirm = { viewModel.renameCategory(d.id, it); dialog = null },
        )
        is ManageDialog.Merge -> MergeDialog(
            source = d.source,
            targets = categories.filter {
                it.category.kind == d.source.category.kind && it.category.id != d.source.category.id && !it.category.archived
            },
            onDismiss = { dialog = null },
            onConfirm = { targetId -> viewModel.mergeCategory(d.source.category.id, targetId); dialog = null },
        )
        ManageDialog.NewAccount -> NewAccountDialog(
            onDismiss = { dialog = null },
            onConfirm = { name, type -> viewModel.createAccount(name, type); dialog = null },
        )
        is ManageDialog.RenameAccount -> NameDialog(
            title = "Renomear conta",
            initial = d.current,
            onDismiss = { dialog = null },
            onConfirm = { viewModel.renameAccount(d.id, it); dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun CategoryRow(item: CategoryWithUsage, onRename: () -> Unit, onMerge: () -> Unit, onToggleArchive: () -> Unit) {
    val c = item.category
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.name, color = if (c.archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(
                (if (c.archived) "Arquivada · " else "") + "${item.usageCount} lançamento(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Opções de ${c.name}" }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; onRename() })
                if (!c.archived) DropdownMenuItem(text = { Text("Mesclar em outra…") }, onClick = { menu = false; onMerge() })
                DropdownMenuItem(text = { Text(if (c.archived) "Reativar" else "Arquivar") }, onClick = { menu = false; onToggleArchive() })
            }
        }
    }
}

@Composable
private fun AccountRow(account: AccountEntity, onRename: () -> Unit, onToggleArchive: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(account.name)
            Text(
                (if (account.archived) "Arquivada · " else "") + accountTypeLabel(account.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Opções de ${account.name}" }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(if (account.archived) "Reativar" else "Arquivar") }, onClick = { menu = false; onToggleArchive() })
            }
        }
    }
}

private fun accountTypeLabel(type: AccountType) = when (type) {
    AccountType.CHECKING -> "Conta corrente"
    AccountType.SAVINGS -> "Poupança"
    AccountType.CASH -> "Dinheiro"
    AccountType.CREDIT_CARD -> "Cartão de crédito"
    AccountType.OTHER -> "Outra"
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(CategoryRepository.MAX_NAME) },
                label = { Text("Nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MergeDialog(source: CategoryWithUsage, targets: List<CategoryWithUsage>, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var target by rememberSaveable { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mesclar \"${source.category.name}\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Os ${source.usageCount} lançamento(s), as contas a pagar e os orçamentos desta categoria passarão para a categoria escolhida, " +
                        "e \"${source.category.name}\" será arquivada.",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { t ->
                        FilterChip(selected = target == t.category.id, onClick = { target = t.category.id }, label = { Text(t.category.name) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { target?.let(onConfirm) }, enabled = target != null) { Text("Mesclar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewAccountDialog(onDismiss: () -> Unit, onConfirm: (String, AccountType) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(AccountType.CHECKING) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova conta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CategoryRepository.MAX_NAME) },
                    label = { Text("Nome (ex.: Nubank)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(accountTypeLabel(t)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, type) }, enabled = name.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
