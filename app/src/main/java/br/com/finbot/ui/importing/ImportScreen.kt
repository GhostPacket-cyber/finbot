package br.com.finbot.ui.importing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.dao.ImportBatchRow
import br.com.finbot.data.entity.CategoryEntity
import br.com.finbot.data.repository.CategoryOrigin
import br.com.finbot.domain.Money
import br.com.finbot.importing.CsvDateFormat
import br.com.finbot.importing.CsvMapping
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd/MM/yy")
private val DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")

/** Importar extrato OFX/CSV (ETAPA 08): escolher arquivo → mapear colunas (CSV) → pré-visualizar → revisão. */
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
    viewModel: ImportViewModel = viewModel(factory = ImportViewModel.Factory),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(uri, context.contentResolver)
    }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        viewModel.messageShown()
    }
    LaunchedEffect(imported) {
        if (imported) {
            viewModel.importedHandled()
            onOpenReview()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    when (step) {
                        is ImportStep.Pick, ImportStep.Loading -> onBack()
                        is ImportStep.Preview -> if ((step as ImportStep.Preview).csv != null) viewModel.backToMapping() else viewModel.cancel()
                        is ImportStep.Mapping -> viewModel.cancel()
                    }
                }) { Text("← Voltar") }
                Text("Importar extrato", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            when (val s = step) {
                ImportStep.Pick -> PickStep(viewModel, onPick = { picker.launch(MIME_TYPES) })
                ImportStep.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is ImportStep.Mapping -> MappingStep(viewModel, s)
                is ImportStep.Preview -> PreviewStep(viewModel, s)
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }
}

/** Bancos exportam OFX/CSV com tipos MIME variados; o conteúdo é verificado depois de ler. */
private val MIME_TYPES = arrayOf("text/*", "application/x-ofx", "application/ofx", "application/vnd.intu.qfx", "application/octet-stream", "application/csv")

@Composable
private fun PickStep(viewModel: ImportViewModel, onPick: () -> Unit) {
    val batches by viewModel.batches.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val accountId by viewModel.accountId.collectAsStateWithLifecycle()
    var undo by remember { mutableStateOf<ImportBatchRow?>(null) }
    val zone = remember { ZoneId.systemDefault() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text(
                "Escolha um extrato OFX ou CSV exportado pelo app ou site do seu banco (até 5 MB). " +
                    "O arquivo é lido só neste aparelho. Os lançamentos entram em revisão e só contam no saldo depois que você confirmar. " +
                    "Reimportar o mesmo extrato não duplica.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val active = accounts.filter { !it.archived }
            if (active.size > 1) {
                ChoiceField(
                    label = "Conta de destino",
                    options = listOf<Pair<Long?, String>>(null to "Conta padrão") + active.map { it.id to it.name },
                    selected = accountId,
                    onSelect = viewModel::selectAccount,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("Escolher arquivo OFX ou CSV") }
            HorizontalDivider()
            Text(
                "Importações anteriores",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
            )
            if (batches.isEmpty()) Text("Nenhuma importação ainda.", style = MaterialTheme.typography.bodySmall)
        }
        items(batches, key = { it.batch.id }) { row ->
            val b = row.batch
            val undone = b.undoneAt != null
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${b.source.name} · ${b.importedAt.atZone(zone).format(DATE_TIME)}" + (b.maskedAccount?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val status = if (undone) {
                            "Desfeito em ${b.undoneAt!!.atZone(zone).format(DATE_TIME)}"
                        } else {
                            "${b.itemCount} importado(s) · ${row.pendingCount} em revisão · ${row.confirmedCount} confirmado(s)" +
                                (if (b.skippedCount > 0) " · ${b.skippedCount} já existiam" else "") +
                                (row.accountName?.let { " · $it" } ?: "")
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!undone) TextButton(onClick = { undo = row }) { Text("Desfazer lote") }
                }
            }
        }
    }

    undo?.let { row ->
        val active = row.pendingCount + row.confirmedCount
        AlertDialog(
            onDismissRequest = { undo = null },
            title = { Text("Desfazer esta importação?") },
            text = {
                Text(
                    "Remove os $active lançamento(s) ainda ativos deste lote, inclusive os já confirmados. " +
                        "Lançamentos manuais e de outros lotes não são afetados. Depois, o mesmo arquivo pode ser importado de novo.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.undoBatch(row.batch.id)
                    undo = null
                }) { Text("Sim, desfazer") }
            },
            dismissButton = { TextButton(onClick = { undo = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MappingStep(viewModel: ImportViewModel, s: ImportStep.Mapping) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val m = s.mapping
    var presetName by remember { mutableStateOf("") }
    val headers = s.rows.getOrNull(m.skipLines - 1).orEmpty()
    val sample = s.rows.drop(m.skipLines).firstOrNull { r -> r.any { it.isNotBlank() } }.orEmpty()
    val columnCount = s.rows.maxOfOrNull { it.size } ?: 0
    val columns: List<Pair<Int?, String>> = (0 until columnCount).map { i ->
        val title = headers.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Coluna ${i + 1}"
        val example = sample.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { " (ex.: ${it.take(18)})" }.orEmpty()
        i to "$title$example"
    }
    val optionalColumns = listOf<Pair<Int?, String>>(null to "Nenhuma") + columns
    val split = m.amountColumn == null

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text(
                if (s.recognized) "Reconheci as colunas abaixo. Confira e toque em Pré-visualizar." else "Indique quais colunas têm data, descrição e valor.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (presets.isNotEmpty()) {
                ChoiceField(
                    label = "Modelo salvo",
                    options = listOf<Pair<Long?, String>>(null to "Escolher…") + presets.map { it.id to it.name },
                    selected = null,
                    onSelect = { id -> presets.firstOrNull { it.id == id }?.let(viewModel::applyPreset) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text("Primeiras linhas do arquivo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            RawRows(s.rows, m.skipLines)

            ChoiceField("Separador", listOf(';' to "Ponto e vírgula ( ; )", ',' to "Vírgula ( , )", '\t' to "Tabulação"), m.separator, {
                viewModel.updateMapping(m.copy(separator = it))
            })
            ChoiceField("Linhas de cabeçalho a pular", (0..10).map { it to it.toString() }, m.skipLines, {
                viewModel.updateMapping(m.copy(skipLines = it))
            })
            ChoiceField("Linhas de rodapé a pular", (0..10).map { it to it.toString() }, m.skipFooterLines, {
                viewModel.updateMapping(m.copy(skipFooterLines = it))
            })
            ChoiceField("Coluna da data", columns, m.dateColumn, { it?.let { c -> viewModel.updateMapping(m.copy(dateColumn = c)) } })
            ChoiceField("Formato da data", CsvDateFormat.entries.map { it to it.label }, m.dateFormat, {
                viewModel.updateMapping(m.copy(dateFormat = it))
            })
            ChoiceField("Coluna da descrição", columns, m.descriptionColumn, { it?.let { c -> viewModel.updateMapping(m.copy(descriptionColumn = c)) } })
            ChoiceField(
                "Valores",
                listOf(false to "Uma coluna de valor (negativo = despesa)", true to "Colunas separadas de débito e crédito"),
                split,
                { useSplit ->
                    viewModel.updateMapping(
                        if (useSplit) m.copy(amountColumn = null, debitColumn = m.debitColumn ?: m.amountColumn, creditColumn = m.creditColumn)
                        else m.copy(amountColumn = m.amountColumn ?: m.debitColumn ?: 0, debitColumn = null, creditColumn = null),
                    )
                },
            )
            if (split) {
                ChoiceField("Coluna de débito (despesas)", optionalColumns, m.debitColumn, { viewModel.updateMapping(m.copy(debitColumn = it)) })
                ChoiceField("Coluna de crédito (receitas)", optionalColumns, m.creditColumn, { viewModel.updateMapping(m.copy(creditColumn = it)) })
            } else {
                ChoiceField("Coluna do valor", columns, m.amountColumn, { viewModel.updateMapping(m.copy(amountColumn = it)) })
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .toggleable(value = m.invertSign, role = Role.Switch, onValueChange = { viewModel.updateMapping(m.copy(invertSign = it)) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Despesas aparecem positivas (inverter sinal)", modifier = Modifier.weight(1f))
                    Switch(checked = m.invertSign, onCheckedChange = null)
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it.take(40) },
                    label = { Text("Nome do modelo (ex.: banco)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { viewModel.savePreset(presetName) }, enabled = presetName.isNotBlank()) { Text("Salvar") }
            }
            Button(onClick = viewModel::previewCsv, enabled = m.isValid, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Text("Pré-visualizar")
            }
        }
    }
}

@Composable
private fun RawRows(rows: List<List<String>>, skip: Int) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        rows.forEachIndexed { i, r ->
            val skipped = i < skip
            Text(
                "${i + 1}: " + r.joinToString(" | ") { it.take(24) },
                style = MaterialTheme.typography.bodySmall,
                color = if (skipped) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PreviewStep(viewModel: ImportViewModel, s: ImportStep.Preview) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val p = s.preview
    val selectable = p.candidates.filter { it.importable }
    val anyUncategorized = selectable.any { s.categoryOf(it.index) == null }
    var showErrors by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                listOfNotNull(
                    p.statement.format.name,
                    p.maskedAccount?.let { "conta $it" },
                    "${p.candidates.size} lido(s)",
                ).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${p.newCount} novo(s) · ${p.alreadyImportedCount} já importado(s) · ${p.possibleDuplicateCount} possível(is) duplicata(s)" +
                    if (p.statement.errors.isNotEmpty()) " · ${p.statement.errors.size} linha(s) com erro" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (p.possibleDuplicateCount > 0) {
                Text(
                    "Possíveis duplicatas parecem com lançamentos que você já registrou (mesmo valor, até 2 dias de diferença). Elas vêm desmarcadas: marque só se forem diferentes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (p.statement.errors.isNotEmpty()) {
                TextButton(onClick = { showErrors = !showErrors }) { Text(if (showErrors) "Ocultar linhas com erro" else "Ver linhas com erro") }
                if (showErrors) {
                    p.statement.errors.take(30).forEach { Text("Linha ${it.line}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selectable.isNotEmpty() && s.selected.size == selectable.size,
                    onCheckedChange = viewModel::toggleAll,
                    enabled = selectable.isNotEmpty(),
                )
                Text("Selecionar todos os novos (${selectable.size})", modifier = Modifier.weight(1f))
            }
            if (anyUncategorized) {
                OutlinedButton(onClick = viewModel::suggestWithAi, enabled = !s.aiBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s.aiBusy) "Consultando a IA…" else "Sugerir categorias com IA (envia só as descrições)")
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(p.candidates, key = { it.index }) { c ->
                val expense = c.item.kind == TransactionKind.EXPENSE
                val amount = (if (expense) "−" else "+") + Money.format(c.item.amountCents)
                val status = when {
                    c.alreadyImported -> "Já importado: será ignorado"
                    c.invalidReason != null -> "Não será importado: ${c.invalidReason}"
                    c.possibleDuplicateOf != null -> "Possível duplicata de \"${c.possibleDuplicateOf.description}\""
                    else -> null
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).semantics(mergeDescendants = false) {
                        contentDescription = "${if (expense) "Despesa" else "Receita"} de ${Money.format(c.item.amountCents)}, ${c.item.description}, ${c.item.date.format(DATE)}" +
                            (status?.let { ", $it" } ?: "")
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = c.index in s.selected, onCheckedChange = { viewModel.toggle(c.index, it) }, enabled = c.importable)
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(c.item.date.format(DATE), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
                            Text(c.item.description, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        CategoryPicker(
                            categories = categories.filter { it.kind == c.item.kind },
                            selected = s.categoryOf(c.index),
                            origin = s.originOf(c.index),
                            enabled = c.importable,
                            onSelect = { viewModel.setCategory(c.index, it) },
                        )
                        status?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (c.possibleDuplicateOf != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    Text(amount, fontWeight = FontWeight.SemiBold, color = if (expense) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
        }
        Button(
            onClick = viewModel::commit,
            enabled = s.selected.isNotEmpty() && !s.saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text(if (s.saving) "Importando…" else "Importar ${s.selected.size} para revisão") }
    }
}

@Composable
private fun CategoryPicker(categories: List<CategoryEntity>, selected: Long?, origin: CategoryOrigin?, enabled: Boolean, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = categories.firstOrNull { it.id == selected }?.name ?: "Sem categoria"
    val originLabel = when (origin) {
        CategoryOrigin.HISTORY -> " (como antes)"
        CategoryOrigin.KEYWORD -> " (sugerida)"
        CategoryOrigin.AI -> " (IA)"
        null -> ""
    }
    Box {
        Text(
            "Categoria: $name$originLabel ▾",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = enabled) { open = true }.padding(vertical = 4.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Sem categoria") }, onClick = { onSelect(null); open = false })
            categories.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelect(c.id); open = false }) }
        }
    }
}

/** Botão com menu de opções; mostra "rótulo: valor". */
@Composable
private fun <T> ChoiceField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: "—"
    Box(modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $current", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}
