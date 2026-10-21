package br.com.finbot.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.finbot.domain.Money
import br.com.finbot.reports.DayPoint
import br.com.finbot.reports.MonthTotals
import br.com.finbot.reports.ReportMath
import br.com.finbot.reports.Slice
import java.time.format.TextStyle
import java.util.Locale

/** Paleta categórica de tons médios, legível em tema claro e escuro. */
val CHART_PALETTE = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFFE15759), Color(0xFF76B7B2), Color(0xFF59A14F), Color(0xFF9C755F),
)
val INCOME_COLOR = Color(0xFF59A14F)
val EXPENSE_COLOR = Color(0xFFE15759)
private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

/** Rosca de despesas por categoria + legenda com valor e percentual. O desenho é decorativo; a legenda e a descrição trazem os números. */
@Composable
fun DonutChart(slices: List<Slice>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.cents }
    val shares = ReportMath.shares(slices)
    val description = "Gráfico de rosca das despesas por categoria. Total ${Money.format(total)}. " +
        slices.mapIndexed { i, s -> "${s.label}: ${Money.format(s.cents)}, ${ReportMath.formatPercent(shares[i]).removePrefix("+")}" }
            .joinToString("; ")
    Column(modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
            Canvas(Modifier.size(180.dp)) {
                val stroke = size.minDimension * 0.18f
                val diameter = size.minDimension - stroke
                val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                var start = -90f
                slices.forEachIndexed { i, s ->
                    val sweep = if (total == 0L) 0f else 360f * s.cents / total
                    drawArc(
                        color = CHART_PALETTE[i % CHART_PALETTE.size],
                        startAngle = start,
                        sweepAngle = (sweep - 1f).coerceAtLeast(0.5f), // pequeno espaço entre fatias
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(diameter, diameter),
                        style = Stroke(width = stroke),
                    )
                    start += sweep
                }
            }
            Text(Money.format(total), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slices.forEachIndexed { i, s ->
                LegendRow(CHART_PALETTE[i % CHART_PALETTE.size], s.label, "${Money.format(s.cents)} · ${ReportMath.formatPercent(shares[i]).removePrefix("+")}")
            }
        }
    }
}

@Composable
fun LegendRow(color: Color, label: String, value: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).background(if (dashed) color.copy(alpha = 0.5f) else color, CircleShape))
        if (value.isEmpty()) {
            // Legenda curta (lado a lado com outras): sem ocupar a largura toda.
            Text(label, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun monthShort(totals: MonthTotals): String =
    totals.month.month.getDisplayName(TextStyle.SHORT, PT_BR).removeSuffix(".").replaceFirstChar { it.uppercase() }

/** Barras agrupadas receitas × despesas por mês. */
@Composable
fun MonthBarsChart(months: List<MonthTotals>, modifier: Modifier = Modifier) {
    val max = months.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) }?.takeIf { it > 0 } ?: 1L
    val description = "Gráfico de barras dos últimos ${months.size} meses. " +
        months.joinToString("; ") { "${monthShort(it)}: receitas ${Money.format(it.incomeCents)}, despesas ${Money.format(it.expenseCents)}" }
    Column(modifier.clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val group = size.width / months.size.coerceAtLeast(1)
            val bar = group * 0.32f
            months.forEachIndexed { i, m ->
                val x = i * group + group * 0.15f
                val incomeH = size.height * m.incomeCents / max
                val expenseH = size.height * m.expenseCents / max
                drawRect(INCOME_COLOR, Offset(x, size.height - incomeH), Size(bar, incomeH))
                drawRect(EXPENSE_COLOR, Offset(x + bar + group * 0.04f, size.height - expenseH), Size(bar, expenseH))
            }
            drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        }
        Row(Modifier.fillMaxWidth()) {
            months.forEach { Text(monthShort(it), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendRow(INCOME_COLOR, "Receitas", "")
            LegendRow(EXPENSE_COLOR, "Despesas", "")
        }
    }
}

/** Linha de despesa acumulada no mês (contínua) comparada ao mês anterior (tracejada). */
@Composable
fun CumulativeLineChart(current: List<DayPoint>, previous: List<DayPoint>, modifier: Modifier = Modifier) {
    val max = (current.map { it.cumulativeCents } + previous.map { it.cumulativeCents }).maxOrNull()?.takeIf { it > 0 } ?: 1L
    val days = maxOf(previous.size, current.size, 28)
    val currentColor = MaterialTheme.colorScheme.primary
    val previousColor = MaterialTheme.colorScheme.outline
    val description = "Gráfico de linha da despesa acumulada. Este mês até o dia ${current.lastOrNull()?.day ?: 0}: " +
        "${Money.format(current.lastOrNull()?.cumulativeCents ?: 0)}. No mesmo dia do mês anterior: " +
        "${Money.format(previous.getOrNull((current.lastOrNull()?.day ?: 1) - 1)?.cumulativeCents ?: 0)}; " +
        "mês anterior completo: ${Money.format(previous.lastOrNull()?.cumulativeCents ?: 0)}."
    Column(modifier.clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            fun path(points: List<DayPoint>): Path = Path().apply {
                points.forEachIndexed { i, p ->
                    val x = size.width * (p.day - 1) / (days - 1).coerceAtLeast(1)
                    val y = size.height - size.height * p.cumulativeCents / max
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            if (previous.isNotEmpty()) {
                drawPath(path(previous), previousColor, style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
            }
            if (current.isNotEmpty()) drawPath(path(current), currentColor, style = Stroke(width = 6f))
            drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendRow(currentColor, "Este mês", "")
            LegendRow(previousColor, "Mês anterior", "", dashed = true)
        }
    }
}
