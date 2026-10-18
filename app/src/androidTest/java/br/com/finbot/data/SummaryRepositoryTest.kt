package br.com.finbot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.domain.TransactionDraft
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class SummaryRepositoryTest {

    private val saoPaulo = ZoneId.of("America/Sao_Paulo")
    private lateinit var f: TestFinance

    @Before
    fun setUp() = runTest {
        // "Agora" depois do período testado, para nenhuma data contar como futura.
        f = TestFinance(MutableClock(Instant.parse("2026-11-05T12:00:00Z")))
        f.seed()
    }

    @After
    fun tearDown() = f.close()

    private suspend fun add(kind: TransactionKind, cents: Long, category: String, at: String) {
        f.transactions.create(
            TransactionDraft(kind, cents, "item", f.categoryId(category, kind), occurredAt = Instant.parse(at)),
            TransactionSource.MANUAL,
        )
    }

    @Test
    fun viradaDoMesUsaOFusoDoUsuario() = runTest {
        add(TransactionKind.EXPENSE, 1_000, "Mercado", "2026-11-01T02:59:00Z") // 31/10 23:59 local
        add(TransactionKind.EXPENSE, 2_000, "Mercado", "2026-11-01T03:00:00Z") // 01/11 00:00 local
        add(TransactionKind.EXPENSE, 4_000, "Mercado", "2026-10-01T02:59:59Z") // 30/09 23:59:59 local

        assertEquals(1_000L, f.summaries.monthly(YearMonth.of(2026, 10), saoPaulo).expenseCents)
        assertEquals(2_000L, f.summaries.monthly(YearMonth.of(2026, 11), saoPaulo).expenseCents)
        assertEquals(4_000L, f.summaries.monthly(YearMonth.of(2026, 9), saoPaulo).expenseCents)
    }

    @Test
    fun resumoMensalComCentavosECategorias() = runTest {
        add(TransactionKind.INCOME, 523_456, "Salário", "2026-10-05T12:00:00Z")
        add(TransactionKind.EXPENSE, 4_590, "Alimentação", "2026-10-10T15:00:00Z")
        add(TransactionKind.EXPENSE, 1, "Alimentação", "2026-10-11T15:00:00Z")
        add(TransactionKind.EXPENSE, 33_333, "Moradia", "2026-10-12T15:00:00Z")
        add(TransactionKind.EXPENSE, 99, "Transporte", "2026-10-13T15:00:00Z")

        val summary = f.summaries.monthly(YearMonth.of(2026, 10), saoPaulo)
        assertEquals(523_456L, summary.incomeCents)
        assertEquals(4_590L + 1 + 33_333 + 99, summary.expenseCents)
        assertEquals(523_456L - 38_023, summary.netCents)
        assertEquals(5, summary.transactionCount)
        assertEquals(listOf("Moradia" to 33_333L, "Alimentação" to 4_591L, "Transporte" to 99L),
            summary.expensesByCategory.map { it.categoryName to it.totalCents })
    }

    @Test
    fun resumoDiarioERelatorioVazio() = runTest {
        add(TransactionKind.EXPENSE, 1_500, "Lazer", "2026-10-17T02:30:00Z") // 16/10 23:30 local
        add(TransactionKind.EXPENSE, 2_500, "Lazer", "2026-10-17T03:30:00Z") // 17/10 00:30 local

        assertEquals(1_500L, f.summaries.daily(LocalDate.of(2026, 10, 16), saoPaulo).expenseCents)
        assertEquals(2_500L, f.summaries.daily(LocalDate.of(2026, 10, 17), saoPaulo).expenseCents)

        val empty = f.summaries.monthly(YearMonth.of(2026, 1), saoPaulo)
        assertEquals(0L, empty.incomeCents)
        assertEquals(0L, empty.expenseCents)
        assertEquals(0, empty.transactionCount)
        assertEquals(emptyList<Any>(), empty.expensesByCategory)
    }

    @Test
    fun muitosLancamentosSomamExatamente() = runTest {
        repeat(300) { i -> add(TransactionKind.EXPENSE, 333, "Outros", "2026-10-${(i % 28 + 1).toString().padStart(2, '0')}T15:00:00Z") }
        assertEquals(300L * 333, f.summaries.monthly(YearMonth.of(2026, 10), saoPaulo).expenseCents)
        assertEquals(-99_900L, f.summaries.balanceCents())
    }
}
