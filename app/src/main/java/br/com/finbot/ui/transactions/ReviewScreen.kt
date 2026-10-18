package br.com.finbot.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.finbot.domain.Money
import br.com.finbot.data.TransactionKind

/**
 * Caixa de revisão: sugestões (`PENDING_REVIEW`) de importações e notificações (ETAPAS 08/09).
 * Nada aqui conta no saldo até ser confirmado; confirmar ou descartar em lote é atômico.
 */
@Composable
fun ReviewScreen(viewModel: TransactionsViewModel, onBack: () -> Unit) {
    val pending by viewModel.pendingReview.collectAsStateWithLifecycle()
    val zone by viewModel.userZone.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Remove da seleção o que já saiu da lista.
    val visibleSelected = selected intersect pending.map { it.transaction.id }.toSet()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Voltar") }
            Text("Revisar lançamentos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        if (pending.isEmpty()) {
            Text(
                "Nada para revisar. Lançamentos sugeridos por extratos importados e notificações de banco aparecerão aqui.",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
            return@Column
        }
        val expense = pending.filter { it.transaction.id in visibleSelected && it.transaction.kind == TransactionKind.EXPENSE }
            .sumOf { it.transaction.amountCents }
        val income = pending.filter { it.transaction.id in visibleSelected && it.transaction.kind == TransactionKind.INCOME }
            .sumOf { it.transaction.amountCents }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = visibleSelected.size == pending.size,
                onCheckedChange = { all -> selected = if (all) pending.map { it.transaction.id }.toSet() else emptySet() },
            )
            Text("Selecionar todos (${pending.size})", modifier = Modifier.weight(1f))
        }
        if (visibleSelected.isNotEmpty()) {
            Text(
                "Selecionados: ${visibleSelected.size} · Despesas ${Money.format(expense)} · Receitas ${Money.format(income)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(pending, key = { it.transaction.id }) { row ->
                val id = row.transaction.id
                TransactionRowItem(
                    row = row,
                    zone = zone,
                    showAccount = false,
                    onClick = { viewModel.openEdit(row) },
                    leading = {
                        Checkbox(
                            checked = id in visibleSelected,
                            onCheckedChange = { on -> selected = if (on) selected + id else selected - id },
                        )
                    },
                )
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmDiscard = true }, enabled = visibleSelected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Descartar (${visibleSelected.size})")
            }
            Button(
                onClick = {
                    viewModel.confirmReview(visibleSelected)
                    selected = emptySet()
                },
                enabled = visibleSelected.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Confirmar (${visibleSelected.size})") }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Descartar ${visibleSelected.size} sugestão(ões)?") },
            text = { Text("Elas não entram no saldo e não voltam numa nova importação do mesmo extrato.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discardReview(visibleSelected)
                    selected = emptySet()
                }) { Text("Descartar") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancelar") } },
        )
    }
}
