package br.com.finbot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.domain.TransactionDraft
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BillAndPendingActionTest {

    private lateinit var f: TestFinance

    @Before
    fun setUp() = runTest {
        f = TestFinance()
        f.seed()
    }

    @After
    fun tearDown() = f.close()

    @Test
    fun contaNaoPagaNaoEDespesa() = runTest {
        val bill = f.bills.createBill("Luz", 18_990, f.categoryId("Moradia", TransactionKind.EXPENSE), LocalDate.of(2026, 10, 20))
        f.bills.ensureOccurrence(bill, LocalDate.of(2026, 10, 20))
        assertEquals(0L, f.summaries.balanceCents())
    }

    @Test
    fun pagarDuasVezesGeraUmUnicoLancamento() = runTest {
        val bill = f.bills.createBill("Internet", 9_990, null, LocalDate.of(2026, 10, 10), BillRepositoryMonthly)
        val occurrence = f.bills.ensureOccurrence(bill, LocalDate.of(2026, 10, 10))
        val paidAt = Instant.parse("2026-10-10T14:00:00Z")

        val first = f.bills.markPaid(occurrence.id, paidAt)
        val second = f.bills.markPaid(occurrence.id, paidAt)

        assertEquals(first, second)
        assertEquals(-9_990L, f.summaries.balanceCents())
        val paid = f.bills.getOccurrence(occurrence.id)!!
        assertEquals(BillOccurrenceStatus.PAID, paid.status)
        assertEquals(first, paid.linkedTransactionId)
        assertEquals(TransactionSource.BILL, f.transactions.get(first)!!.source)
    }

    @Test
    fun ocorrenciaEIdempotentePorVencimento() = runTest {
        val bill = f.bills.createBill("Aluguel", 150_000, null, LocalDate.of(2026, 10, 5), BillRepositoryMonthly)
        val a = f.bills.ensureOccurrence(bill, LocalDate.of(2026, 10, 5))
        val b = f.bills.ensureOccurrence(bill, LocalDate.of(2026, 10, 5))
        val c = f.bills.ensureOccurrence(bill, LocalDate.of(2026, 11, 5))
        assertEquals(a.id, b.id)
        assertTrue(a.id != c.id)
    }

    @Test
    fun desfazerPagamentoOuExcluirLancamentoReabreAConta() = runTest {
        val bill = f.bills.createBill("Água", 7_000, null, LocalDate.of(2026, 10, 15))
        val occ = f.bills.ensureOccurrence(bill, LocalDate.of(2026, 10, 15))
        f.bills.markPaid(occ.id, Instant.parse("2026-10-15T12:00:00Z"))
        f.bills.unmarkPaid(occ.id)

        assertEquals(0L, f.summaries.balanceCents())
        assertEquals(BillOccurrenceStatus.PENDING, f.bills.getOccurrence(occ.id)!!.status)

        // Pagar de novo depois de desfazer cria um novo lançamento, e o saldo conta só uma vez.
        val again = f.bills.markPaid(occ.id, Instant.parse("2026-10-16T12:00:00Z"))
        assertEquals(-7_000L, f.summaries.balanceCents())
        f.transactions.softDelete(again)
        assertEquals(BillOccurrenceStatus.PENDING, f.bills.getOccurrence(occ.id)!!.status)
        assertNull(f.bills.getOccurrence(occ.id)!!.linkedTransactionId)
    }

    @Test
    fun acaoPendenteValeUmaUnicaVez() = runTest {
        val id = f.pending.create("{\"tipo\":\"gasto\"}", ActionOrigin.CHAT)
        var executions = 0
        val draft = TransactionDraft(TransactionKind.EXPENSE, 1_000, "café", null, occurredAt = f.clock.current)

        val first = f.pending.confirm(id) { executions++; f.transactions.create(draft, TransactionSource.CHAT) }
        val second = f.pending.confirm(id) { executions++; f.transactions.create(draft, TransactionSource.CHAT) }

        assertTrue(first != null)
        assertNull(second)
        assertEquals(1, executions)
        assertEquals(-1_000L, f.summaries.balanceCents())
        assertFalse(f.pending.cancel(id))
    }

    @Test
    fun acaoExpiradaNaoPodeSerConfirmada() = runTest {
        val id = f.pending.create("{}", ActionOrigin.CHAT, Duration.ofMinutes(15))
        f.clock.advance(Duration.ofMinutes(16))
        assertNull(f.pending.confirm(id) { "executou" })
        assertFalse(f.pending.cancel(id))
        // Expirada, mas mantida para o chat mostrar o status; só sai após a retenção de 30 dias.
        assertEquals(0, f.pending.purge())
        f.clock.advance(Duration.ofDays(31))
        assertEquals(1, f.pending.purge())
    }

    @Test
    fun falhaAoConfirmarReverteEAcaoContinuaPendente() = runTest {
        val id = f.pending.create("{}", ActionOrigin.CHAT)
        runCatching { f.pending.confirm(id) { error("falha simulada") } }
        assertEquals(PendingActionStatus.PENDING, f.pending.get(id)!!.status)
        assertEquals("ok", f.pending.confirm(id) { "ok" })
    }

    @Test
    fun acaoCanceladaNaoExecuta() = runTest {
        val id = f.pending.create("{}", ActionOrigin.CHAT)
        assertTrue(f.pending.cancel(id))
        assertNull(f.pending.confirm(id) { "executou" })
    }

    private companion object {
        const val BillRepositoryMonthly = br.com.finbot.data.repository.BillRepository.MONTHLY
    }
}
