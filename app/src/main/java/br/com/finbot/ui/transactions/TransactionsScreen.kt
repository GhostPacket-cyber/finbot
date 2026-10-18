package br.com.finbot.ui.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.dao.TransactionRow
import br.com.finbot.domain.Money
import br.com.finbot.domain.PeriodPreset
import br.com.finbot.domain.label
import br.com.finbot.ui.categories.ManageScreen
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Sub { LIST, REVIEW, MANAGE }

/** Aba Lançamentos com subtelas por estado: lista, revisão e categorias/contas. */
@Composable
fun TransactionsTab(viewModel: TransactionsViewModel = viewModel(factory = TransactionsViewModel.Factory)) {
    var sub by rememberSaveable { mutableStateOf(Sub.LIST) }
    BackHandler(enabled = sub != Sub.LIST) { sub = Sub.LIST }
    val snackbar = remember { SnackbarHostState() }
    val event by viewModel.event.collectAsStateWithLifecycle()

    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        // Limpa o evento só depois de exibir: limpar antes muda a chave e cancela este efeito (o snackbar sumia).
        val result = snackbar.showSnackbar(
            message = e.message,
            actionLabel = if (e.undoDeletedId != null) "Desfazer" else null,
            duration = if (e.undoDeletedId != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        viewModel.eventShown()
        if (result == SnackbarResult.ActionPerformed && e.undoDeletedId != null) viewModel.undoDelete(e.undoDeletedId)
    }

    Box(Modifier.fillMaxSize()) {
        when (sub) {
            Sub.LIST -> TransactionsListScreen(viewModel, onOpenReview = { sub = Sub.REVIEW }, onOpenManage = { sub = Sub.MANAGE })
            Sub.REVIEW -> ReviewScreen(viewModel, onBack = { sub = Sub.LIST })
            Sub.MANAGE -> ManageScreen(onBack = { sub = Sub.LIST })
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    val form by viewModel.form.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    form?.let {
        TransactionFormDialog(
            state = it,
            categories = categories,
            accounts = accounts,
            onChange = viewModel::updateForm,
            onSave = viewModel::saveForm,
            onDelete = viewModel::delete,
            onDismiss = viewModel::closeForm,
        )
    }
}

@Composable
private fun TransactionsListScreen(viewModel: TransactionsViewModel, onOpenReview: () -> Unit, onOpenManage: () -> Unit) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val pending by viewModel.pendingReview.collectAsStateWithLifecycle()
    val zone by viewModel.userZone.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Lançamentos", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onOpenManage) { Text("Categorias e contas") }
            }

            if (pending.isNotEmpty()) {
                Card(
                    onClick = onOpenReview,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        "${pending.size} lançamento(s) para revisar →",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            OutlinedTextField(
                value = filter.search,
                onValueChange = { viewModel.setSearch(it.take(60)) },
                label = { Text("Buscar na descrição ou observação") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip(
                    label = filter.period.label,
                    selected = filter.period != PeriodPreset.THIS_MONTH,
                    options = PeriodPreset.entries.map { it.label to it },
                    onSelect = { viewModel.setPeriod(it ?: PeriodPreset.THIS_MONTH) },
                    allowClear = false,
                )
                ChoiceChip(
                    label = when (filter.kind) { TransactionKind.EXPENSE -> "Despesas"; TransactionKind.INCOME -> "Receitas"; null -> "Tipo" },
                    selected = filter.kind != null,
                    options = listOf("Despesas" to TransactionKind.EXPENSE, "Receitas" to TransactionKind.INCOME),
                    onSelect = viewModel::setKind,
                )
                val categoryOptions = categories
                    .filter { filter.kind == null || it.kind == filter.kind }
                    .map { c ->
                        val name = if (c.archived) "${c.name} (arquivada)" else c.name
                        val suffix = if (filter.kind == null) " · ${kindShort(c.kind)}" else ""
                        (name + suffix) to c.id
                    }
                ChoiceChip(
                    label = categories.firstOrNull { it.id == filter.categoryId }?.name ?: "Categoria",
                    selected = filter.categoryId != null,
                    options = categoryOptions,
                    onSelect = viewModel::setCategory,
                )
                if (accounts.size > 1) {
                    ChoiceChip(
                        label = accounts.firstOrNull { it.id == filter.accountId }?.name ?: "Conta",
                        selected = filter.accountId != null,
                        options = accounts.map { it.name to it.id },
                        onSelect = viewModel::setAccount,
                    )
                }
                ChoiceChip(
                    label = filter.source?.label() ?: "Origem",
                    selected = filter.source != null,
                    options = TransactionSource.entries.map { it.label() to it },
                    onSelect = viewModel::setSource,
                )
                if (!filter.isDefault) TextButton(onClick = viewModel::clearFilters) { Text("Limpar filtros") }
            }

            totals?.let { t ->
                Text(
                    "Receitas ${Money.format(t.incomeCents)} · Despesas ${Money.format(t.expenseCents)} · ${t.count} lançamento(s)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider()

            val list = rows
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyList(filtered = !filter.isDefault, onClear = viewModel::clearFilters)
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(list, key = { it.transaction.id }) { row ->
                        TransactionRowItem(row, zone, showAccount = accounts.size > 1, onClick = { viewModel.openEdit(row) })
                        HorizontalDivider()
                    }
                    if (hasMore) {
                        item {
                            // Carrega a próxima página ao chegar ao fim; o botão serve a leitores de tela.
                            LaunchedEffect(list.size) { viewModel.loadMore() }
                            OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("Carregar mais")
                            }
                        }
                    }
                }
            }
        }
        // FAB simples com texto fixo: o ExtendedFloatingActionButton deixava o rótulo invisível neste aparelho
        // (animação de expansão), e o botão ficava só com o "+".
        FloatingActionButton(
            onClick = { viewModel.openNew(filter.kind ?: TransactionKind.EXPENSE) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Text("+ Novo lançamento", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun kindShort(kind: TransactionKind) = if (kind == TransactionKind.EXPENSE) "despesa" else "receita"

/** Chip que abre um menu de opções; "Todas" limpa o filtro. */
@Composable
private fun <T> ChoiceChip(
    label: String,
    selected: Boolean,
    options: List<Pair<String, T>>,
    onSelect: (T?) -> Unit,
    allowClear: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected, onClick = { open = true }, label = { Text("$label ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowClear) {
                DropdownMenuItem(text = { Text("Todas") }, onClick = { open = false; onSelect(null) })
            }
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

private val ROW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

@Composable
fun TransactionRowItem(
    row: TransactionRow,
    zone: ZoneId,
    showAccount: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val t = row.transaction
    val expense = t.kind == TransactionKind.EXPENSE
    val amount = (if (expense) "−" else "+") + Money.format(t.amountCents)
    val date = t.occurredAt.atZone(zone).toLocalDate().format(ROW_DATE)
    val details = listOfNotNull(row.categoryName ?: "Sem categoria", if (showAccount) row.accountName else null, t.source.label())
        .joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics {
                contentDescription = "${if (expense) "Despesa" else "Receita"} de ${Money.format(t.amountCents)}, ${t.description}, $date, $details"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(t.description, style = MaterialTheme.typography.bodyLarge)
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            amount,
            fontWeight = FontWeight.SemiBold,
            color = if (expense) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyList(filtered: Boolean, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (filtered) {
            Text("Nenhum lançamento com esses filtros.", textAlign = TextAlign.Center)
            TextButton(onClick = onClear) { Text("Limpar filtros") }
        } else {
            Text(
                "Nenhum lançamento neste mês. Registre pelo chat (\"gastei 45 no mercado\") ou toque em + Novo lançamento.",
                textAlign = TextAlign.Center,
            )
        }
    }
}
