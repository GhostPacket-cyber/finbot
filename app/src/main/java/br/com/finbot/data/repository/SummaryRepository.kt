package br.com.finbot.data.repository

import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.dao.CategoryTotal
import br.com.finbot.domain.InstantRange
import br.com.finbot.domain.Periods
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class PeriodSummary(
    val range: InstantRange,
    val incomeCents: Long,
    val expenseCents: Long,
    val transactionCount: Int,
    val expensesByCategory: List<CategoryTotal>,
    val incomeByCategory: List<CategoryTotal>,
) {
    /** Resultado do período (receitas − despesas). Não é saldo bancário. */
    val netCents: Long get() = incomeCents - expenseCents
}

/**
 * Agregações calculadas no SQLite. Considera apenas lançamentos CONFIRMED e não excluídos;
 * contas não pagas e sugestões em revisão ficam de fora.
 */
class SummaryRepository(db: FinBotDatabase) {
    private val dao = db.transactionDao()

    /** Saldo calculado = receitas confirmadas − despesas confirmadas. Não é o saldo bancário real. */
    suspend fun balanceCents(): Long = dao.balanceCents()

    fun observeBalanceCents(): Flow<Long> = dao.observeBalanceCents()

    suspend fun summary(range: InstantRange): PeriodSummary {
        val totals = dao.totalsBetween(range.start, range.end)
        return PeriodSummary(
            range = range,
            incomeCents = totals.incomeCents,
            expenseCents = totals.expenseCents,
            transactionCount = totals.count,
            expensesByCategory = dao.totalsByCategoryBetween(TransactionKind.EXPENSE, range.start, range.end),
            incomeByCategory = dao.totalsByCategoryBetween(TransactionKind.INCOME, range.start, range.end),
        )
    }

    suspend fun daily(date: LocalDate, zone: ZoneId): PeriodSummary = summary(Periods.day(date, zone))

    suspend fun monthly(month: YearMonth, zone: ZoneId): PeriodSummary = summary(Periods.month(month, zone))
}
