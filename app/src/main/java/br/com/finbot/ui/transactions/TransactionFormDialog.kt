package br.com.finbot.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.entity.CategoryEntity
import br.com.finbot.domain.TransactionFormInput
import br.com.finbot.domain.TransactionRules

/** Novo lançamento ou edição. "Salvar" é a confirmação explícita; "Excluir" pede confirmação à parte. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionFormDialog(
    state: FormState,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    onChange: ((TransactionFormInput) -> TransactionFormInput) -> Unit,
    onSave: () -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val input = state.input
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val editing = state.transactionId != null

    // Ativas do tipo escolhido; ao editar, mantém visível a categoria atual mesmo se arquivada.
    val kindCategories = categories.filter { it.kind == input.kind && (!it.archived || it.id == input.categoryId) }
    val visibleAccounts = accounts.filter { !it.archived || it.id == input.accountId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    !editing -> "Novo lançamento"
                    state.isPendingReview -> "Editar sugestão"
                    else -> "Editar lançamento"
                },
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tipo", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TransactionKind.EXPENSE to "Despesa", TransactionKind.INCOME to "Receita").forEach { (kind, label) ->
                        FilterChip(
                            selected = input.kind == kind,
                            onClick = {
                                onChange {
                                    val keep = categories.firstOrNull { c -> c.id == it.categoryId }?.kind == kind
                                    it.copy(kind = kind, categoryId = if (keep) it.categoryId else null)
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = input.amount,
                    onValueChange = { v -> onChange { it.copy(amount = v.take(20)) } },
                    label = { Text("Valor (R$)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = state.errors.amount != null,
                    supportingText = state.errors.amount?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.description,
                    onValueChange = { v -> onChange { it.copy(description = v.take(TransactionRules.MAX_DESCRIPTION)) } },
                    label = { Text("Descrição") },
                    singleLine = true,
                    isError = state.errors.description != null,
                    supportingText = state.errors.description?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.date,
                    onValueChange = { v -> onChange { it.copy(date = v.take(10)) } },
                    label = { Text("Data (dd/mm/aaaa)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = state.errors.date != null,
                    supportingText = state.errors.date?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Categoria", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kindCategories.forEach { c ->
                        FilterChip(
                            selected = c.id == input.categoryId,
                            onClick = { onChange { it.copy(categoryId = if (it.categoryId == c.id) null else c.id) } },
                            label = { Text(if (c.archived) "${c.name} (arquivada)" else c.name) },
                        )
                    }
                }
                if (visibleAccounts.size > 1) {
                    Text("Conta", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleAccounts.forEach { a ->
                            FilterChip(
                                selected = a.id == input.accountId,
                                onClick = { onChange { it.copy(accountId = a.id) } },
                                label = { Text(a.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input.note,
                    onValueChange = { v -> onChange { it.copy(note = v.take(TransactionRules.MAX_NOTE)) } },
                    label = { Text("Observação (opcional)") },
                    maxLines = 3,
                    isError = state.errors.note != null,
                    supportingText = state.errors.note?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.errors.general.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                if (editing) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Excluir lançamento", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !state.saving) { Text(if (state.saving) "Salvando…" else "Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )

    if (confirmDelete && state.transactionId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir lançamento?") },
            text = { Text("Ele sai do saldo e dos relatórios. Você poderá desfazer logo em seguida.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(state.transactionId)
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}
