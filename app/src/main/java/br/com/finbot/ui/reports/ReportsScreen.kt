package br.com.finbot.ui.reports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.finbot.data.repository.MonthlyReport
import br.com.finbot.domain.Money
import br.com.finbot.reports.ReportMath
import br.com.finbot.ui.bills.dueLabel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR"))

fun monthTitle(m: YearMonth): String = m.format(MONTH_TITLE).replaceFirstChar { it.uppercase() }

@Composable
fun ReportsScreen(viewModel: ReportsViewModel = viewModel(factory = ReportsViewModel.Factory)) {
    val month by viewModel.month.collectAsStateWithLifecycle()
    val current by viewModel.currentMonth.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.writeExport(uri, context.contentResolver)
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Relatórios", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.shiftMonth(-1) }, modifier = Modifier.semantics { contentDescription = "Mês anterior" }) { Text("◀") }
                Text(month?.let(::monthTitle).orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.shiftMonth(1) },
                    enabled = month != null && month != current,
                    modifier = Modifier.semantics { contentDescription = "Próximo mês" },
                ) { Text("▶") }
            }

            val r = report
            if (r == null) {
                Text("Calculando…")
            } else {
                SummaryCard(r)
                BillsCard(r)
                if (r.categories.isNotEmpty()) {
                    ChartCard("Despesas por categoria", table = { CategoryTable(r) }) { DonutChart(r.categories) }
                }
                if (r.cumulative.isNotEmpty() || r.previousCumulative.any { it.cumulativeCents > 0 }) {
                    ChartCard("Despesa acumulada no mês", table = { CumulativeTable(r) }) {
                        CumulativeLineChart(r.cumulative, r.previousCumulative)
                    }
                }
                ChartCard("Últimos ${r.evolution.size} meses", table = { EvolutionTable(r) }) { MonthBarsChart(r.evolution) }
            }

            if (upcoming.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Próximas contas (7 dias)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        val today = LocalDate.now()
                        upcoming.forEach { Text("${it.title} · ${Money.format(it.amountCents)} · ${dueLabel(it.dueDate, today)}") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Exportar (CSV)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        "Planilha com lançamentos confirmados (abre no Excel e no Google Planilhas). " +
                            "O arquivo contém seus dados financeiros e fica onde você escolher salvar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { createDocument.launch(viewModel.prepareExport(ExportScope.MONTH)) }) { Text("Exportar mês") }
                        OutlinedButton(onClick = { createDocument.launch(viewModel.prepareExport(ExportScope.ALL)) }) { Text("Exportar tudo") }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun SummaryCard(r: MonthlyReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Resumo do mês", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (r.transactionCount == 0) {
                Text("Nenhum lançamento confirmado neste mês.", style = MaterialTheme.typography.bodyMedium)
            }
            ValueRow("Receitas", Money.format(r.totals.incomeCents))
            ValueRow("Despesas", Money.format(r.totals.expenseCents))
            ValueRow("Resultado", Money.format(r.totals.netCents), bold = true)
            ValueRow(
                "Despesas vs mês anterior",
                r.expenseVariation?.let { ReportMath.formatPercent(it) } ?: "sem base de comparação",
            )
            r.dailyAverageExpense?.let { ValueRow("Média diária de despesas", Money.format(it)) }
            Text(
                "Saldo calculado pelo FinBot (receitas − despesas registradas), não o saldo bancário.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BillsCard(r: MonthlyReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Contas do mês", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            ValueRow("Pagas (${r.billsPaid.count})", Money.format(r.billsPaid.cents))
            ValueRow("Pendentes (${r.billsPending.count})", Money.format(r.billsPending.cents))
            Text(
                "Pendentes são previsão: só entram nas despesas quando pagas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Card de gráfico com alternativa em tabela (acessibilidade e leitura precisa). */
@Composable
private fun ChartCard(title: String, table: @Composable () -> Unit, chart: @Composable () -> Unit) {
    var showTable by rememberSaveable(title) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = { showTable = !showTable }) { Text(if (showTable) "Ver gráfico" else "Ver tabela") }
            }
            if (showTable) table() else chart()
        }
    }
}

@Composable
private fun CategoryTable(r: MonthlyReport) {
    val shares = ReportMath.shares(r.categories)
    r.categories.forEachIndexed { i, s -> ValueRow(s.label, "${Money.format(s.cents)} (${ReportMath.formatPercent(shares[i]).removePrefix("+")})") }
}

@Composable
private fun CumulativeTable(r: MonthlyReport) {
    val last = r.cumulative.lastOrNull()
    ValueRow("Este mês até o dia ${last?.day ?: 0}", Money.format(last?.cumulativeCents ?: 0))
    val sameDay = r.previousCumulative.getOrNull((last?.day ?: 1) - 1)
    ValueRow("Mês anterior no mesmo dia", Money.format(sameDay?.cumulativeCents ?: 0))
    ValueRow("Mês anterior completo", Money.format(r.previousCumulative.lastOrNull()?.cumulativeCents ?: 0))
}

@Composable
private fun EvolutionTable(r: MonthlyReport) {
    r.evolution.forEach { m ->
        ValueRow(monthTitle(m.month), "+${Money.format(m.incomeCents)} / −${Money.format(m.expenseCents)}")
    }
}
