package br.com.finbot.data.repository

import br.com.finbot.data.BillOccurrenceStatus
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.dao.OccurrenceView
import br.com.finbot.data.dao.TransactionRow
import br.com.finbot.domain.InstantRange
import br.com.finbot.domain.Periods
import br.com.finbot.reports.DayPoint
import br.com.finbot.reports.MonthTotals
import br.com.finbot.reports.ReportMath
import br.com.finbot.reports.Slice
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class CountAndTotal(val count: Int, val cents: Long)

/** Relatório de um mês. Todo valor sai do SQLite (lançamentos CONFIRMED não excluídos), nunca da IA. */
data class MonthlyReport(
    val month: YearMonth,
    val totals: MonthTotals,
    val previous: MonthTotals,
    val transactionCount: Int,
    val expenseVariation: Double?,
    val incomeVariation: Double?,
    val dailyAverageExpense: Long?,
    /** Despesas por categoria já agrupadas em top 5 + "Demais". */
    val categories: List<Slice>,
    /** Contas pagas com vencimento no mês (realizado, já incluído nas despesas pelo lançamento do pagamento). */
    val billsPaid: CountAndTotal,
    /** Contas pendentes com vencimento no mês: previsto, NÃO incluído nas despesas. */
    val billsPending: CountAndTotal,
    val cumulative: List<DayPoint>,
    val previousCumulative: List<DayPoint>,
    /** Últimos [EVOLUTION_MONTHS] meses terminando em [month]. */
    val evolution: List<MonthTotals>,
) {
    val isEmpty: Boolean get() = transactionCount == 0 && billsPaid.count == 0 && billsPending.count == 0
}

class ReportRepository(private val db: FinBotDatabase) {
    private val tx = db.transactionDao()
    private val bills = db.billDao()

    suspend fun monthly(month: YearMonth, zone: ZoneId, today: LocalDate): MonthlyReport {
        val range = Periods.month(month, zone)
        val current = totals(month, zone)
        val previous = totals(month.minusMonths(1), zone)
        val count = tx.totalsBetween(range.start, range.end).count
        val categories = tx.totalsByCategoryBetween(TransactionKind.EXPENSE, range.start, range.end)
            .map { Slice(it.categoryName ?: "Sem categoria", it.totalCents) }
        val billTotals = bills.totalsByStatusBetween(month.atDay(1), month.atEndOfMonth())
        val paid = billTotals.firstOrNull { it.status == BillOccurrenceStatus.PAID }
        val pending = billTotals.firstOrNull { it.status == BillOccurrenceStatus.PENDING }
        val lastDay = when {
            month == YearMonth.from(today) -> today.dayOfMonth
            month.isAfter(YearMonth.from(today)) -> 0
            else -> month.lengthOfMonth()
        }
        return MonthlyReport(
            month = month,
            totals = current,
            previous = previous,
            transactionCount = count,
            expenseVariation = ReportMath.variationPercent(current.expenseCents, previous.expenseCents),
            incomeVariation = ReportMath.variationPercent(current.incomeCents, previous.incomeCents),
            dailyAverageExpense = ReportMath.dailyAverage(current.expenseCents, month, today),
            categories = ReportMath.topSlices(categories),
            billsPaid = CountAndTotal(paid?.count ?: 0, paid?.totalCents ?: 0),
            billsPending = CountAndTotal(pending?.count ?: 0, pending?.totalCents ?: 0),
            cumulative = ReportMath.cumulative(dailyExpenses(range, zone), lastDay),
            previousCumulative = ReportMath.cumulative(
                dailyExpenses(Periods.month(month.minusMonths(1), zone), zone),
                month.minusMonths(1).lengthOfMonth(),
            ),
            evolution = (EVOLUTION_MONTHS - 1 downTo 0).map { totals(month.minusMonths(it.toLong()), zone) },
        )
    }

    /** Próximas contas pendentes (inclui atrasadas) até [until]. */
    suspend fun upcomingBills(until: LocalDate): List<OccurrenceView> = bills.listPendingUntil(until)

    /** Lançamentos confirmados para exportação; [range] null = todos. */
    suspend fun exportRows(range: InstantRange?): List<TransactionRow> =
        tx.observeFiltered(range?.start, range?.end, null, null, null, null, null, Int.MAX_VALUE).first()
            .sortedWith(compareBy({ it.transaction.occurredAt }, { it.transaction.id }))

    private suspend fun totals(month: YearMonth, zone: ZoneId): MonthTotals {
        val r = Periods.month(month, zone)
        val t = tx.totalsBetween(r.start, r.end)
        return MonthTotals(month, t.incomeCents, t.expenseCents)
    }

    private suspend fun dailyExpenses(range: InstantRange, zone: ZoneId): Map<Int, Long> =
        tx.listConfirmedBetween(range.start, range.end, Int.MAX_VALUE, 0)
            .filter { it.kind == TransactionKind.EXPENSE }
            .groupBy { it.occurredAt.atZone(zone).dayOfMonth }
            .mapValues { (_, list) -> list.sumOf { it.amountCents } }

    companion object {
        const val EVOLUTION_MONTHS = 6
    }
}
