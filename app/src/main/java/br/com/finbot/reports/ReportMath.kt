package br.com.finbot.reports

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

/** Fatia de gráfico (rosca/barras). [cents] sempre ≥ 0. */
data class Slice(val label: String, val cents: Long)

/** Ponto de série diária acumulada (dia do mês → total acumulado em centavos). */
data class DayPoint(val day: Int, val cumulativeCents: Long)

data class MonthTotals(val month: YearMonth, val incomeCents: Long, val expenseCents: Long) {
    val netCents: Long get() = incomeCents - expenseCents
}

/** Cálculos puros dos relatórios. Valores sempre em centavos; percentuais só para exibição. */
object ReportMath {
    const val TOP_CATEGORIES = 5
    const val OTHERS_LABEL = "Demais"

    /** Variação percentual de [current] sobre [previous]; null quando não há base (mês anterior zerado). */
    fun variationPercent(current: Long, previous: Long): Double? =
        if (previous == 0L) null else (current - previous) * 100.0 / previous

    /**
     * Despesa média por dia. No mês corrente divide pelos dias já transcorridos (inclusive hoje);
     * em meses passados, pelo total de dias do mês; meses futuros não têm média.
     */
    fun dailyAverage(expenseCents: Long, month: YearMonth, today: LocalDate): Long? {
        val days = when {
            month == YearMonth.from(today) -> today.dayOfMonth
            month.isBefore(YearMonth.from(today)) -> month.lengthOfMonth()
            else -> return null
        }
        return (expenseCents.toDouble() / days).roundToLong()
    }

    /** As [TOP_CATEGORIES] maiores; o restante somado em "Demais". A soma das fatias é igual à soma de entrada. */
    fun topSlices(items: List<Slice>, top: Int = TOP_CATEGORIES): List<Slice> {
        val sorted = items.filter { it.cents > 0 }.sortedWith(compareByDescending<Slice> { it.cents }.thenBy { it.label })
        if (sorted.size <= top + 1) return sorted
        val head = sorted.take(top)
        val rest = sorted.drop(top).sumOf { it.cents }
        return head + Slice(OTHERS_LABEL, rest)
    }

    /** Participação de cada fatia em % (0–100), para rótulos. */
    fun shares(slices: List<Slice>): List<Double> {
        val total = slices.sumOf { it.cents }
        if (total == 0L) return slices.map { 0.0 }
        return slices.map { it.cents * 100.0 / total }
    }

    /** Série acumulada por dia: [daily] mapeia dia do mês → total do dia. Vai até [lastDay]. */
    fun cumulative(daily: Map<Int, Long>, lastDay: Int): List<DayPoint> {
        var acc = 0L
        return (1..lastDay).map { day ->
            acc += daily[day] ?: 0L
            DayPoint(day, acc)
        }
    }

    fun formatPercent(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        val text = if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString().replace('.', ',')
        return if (value > 0) "+$text%" else "$text%"
    }
}
