package br.com.finbot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.data.repository.AuditLogger
import br.com.finbot.domain.InvalidTransactionException
import br.com.finbot.domain.TransactionDraft
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TransactionRepositoryTest {

    private lateinit var f: TestFinance

    @Before
    fun setUp() = runTest {
        f = TestFinance()
        f.seed()
    }

    @After
    fun tearDown() = f.close()

    private suspend fun expense(cents: Long, category: String = "Alimentação", at: Instant = f.clock.current) =
        TransactionDraft(TransactionKind.EXPENSE, cents, "gasto", f.categoryId(category, TransactionKind.EXPENSE), occurredAt = at)

    private suspend fun income(cents: Long) =
        TransactionDraft(TransactionKind.INCOME, cents, "salário", f.categoryId("Salário", TransactionKind.INCOME), occurredAt = f.clock.current)

    @Test
    fun saldoSomaReceitasMenosDespesasConfirmadas() = runTest {
        f.transactions.create(income(250_000), TransactionSource.MANUAL)
        f.transactions.create(expense(4_590), TransactionSource.CHAT)
        f.transactions.create(expense(10), TransactionSource.MANUAL)
        assertEquals(250_000L - 4_590 - 10, f.summaries.balanceCents())
    }

    @Test
    fun pendenteDeRevisaoEDescartadoNaoEntramNoSaldo() = runTest {
        f.transactions.create(income(100_000), TransactionSource.MANUAL)
        val pending = f.transactions.create(expense(30_000), TransactionSource.IMPORT_OFX, TransactionStatus.PENDING_REVIEW)
        val toDiscard = f.transactions.create(expense(5_000), TransactionSource.IMPORT_OFX, TransactionStatus.PENDING_REVIEW)
        assertEquals(100_000L, f.summaries.balanceCents())

        f.transactions.discard(toDiscard)
        assertEquals(100_000L, f.summaries.balanceCents())

        f.transactions.confirm(pending)
        assertEquals(70_000L, f.summaries.balanceCents())
    }

    @Test
    fun exclusaoLogicaRemoveDoSaldoMasMantemRegistroEAuditoria() = runTest {
        val id = f.transactions.create(expense(4_500), TransactionSource.MANUAL)
        f.transactions.softDelete(id)

        assertEquals(0L, f.summaries.balanceCents())
        val row = f.transactions.get(id)
        assertNotNull(row?.deletedAt)
        val actions = f.db.auditLogDao().listFor(AuditLogger.TRANSACTION, row!!.uuid).map { it.action }
        assertEquals(listOf(AuditAction.CREATE, AuditAction.DELETE), actions)
    }

    @Test
    fun edicaoEAuditadaSemGuardarDescricaoAntiga() = runTest {
        val id = f.transactions.create(expense(4_500), TransactionSource.MANUAL)
        f.transactions.update(id, expense(5_000).copy(description = "almoço corrigido"))

        val row = f.transactions.get(id)!!
        assertEquals(5_000L, row.amountCents)
        val update = f.db.auditLogDao().listFor(AuditLogger.TRANSACTION, row.uuid).last()
        assertEquals(AuditAction.UPDATE, update.action)
        assertTrue(update.summaryJson.contains("4500→5000"))
        assertFalse(update.summaryJson.contains("almoço corrigido"))
    }

    @Test
    fun deduplicacaoPorChave() = runTest {
        val draft = expense(4_590)
        val first = f.transactions.createIfNew(draft, TransactionSource.IMPORT_OFX, dedupKey = "ofx:acc1:FIT123")
        val second = f.transactions.createIfNew(draft, TransactionSource.IMPORT_OFX, dedupKey = "ofx:acc1:FIT123")
        assertNotNull(first)
        assertNull(second)
        // Mesmo depois de descartado, o item não volta numa reimportação.
        f.transactions.discard(first!!)
        assertNull(f.transactions.createIfNew(draft, TransactionSource.IMPORT_OFX, dedupKey = "ofx:acc1:FIT123"))
    }

    @Test
    fun categoriaDeTipoErradoOuArquivadaERejeitada() = runTest {
        val salario = f.categoryId("Salário", TransactionKind.INCOME)
        try {
            f.transactions.create(expense(100).copy(categoryId = salario), TransactionSource.MANUAL)
            fail("deveria rejeitar categoria de receita em despesa")
        } catch (_: InvalidTransactionException) {
        }
        val lazer = f.categoryId("Lazer", TransactionKind.EXPENSE)
        f.categories.archive(lazer)
        try {
            f.transactions.create(expense(100, "Lazer"), TransactionSource.MANUAL)
            fail("deveria rejeitar categoria arquivada")
        } catch (_: InvalidTransactionException) {
        }
    }

    @Test
    fun valoresInvalidosNaoChegamAoBanco() = runTest {
        listOf(0L, -100L).forEach { cents ->
            try {
                f.transactions.create(expense(cents), TransactionSource.MANUAL)
                fail("valor $cents aceito")
            } catch (_: InvalidTransactionException) {
            }
        }
        assertEquals(0L, f.summaries.balanceCents())
    }

    @Test
    fun contaPadraoECategoriasPadraoSaoIdempotentes() = runTest {
        assertEquals(0, f.categories.ensureDefaults())
        val first = f.accounts.ensureDefault()
        val second = f.accounts.ensureDefault()
        assertEquals(first.id, second.id)
        assertEquals(1, f.accounts.listActive().size)
        val id = f.transactions.create(expense(100), TransactionSource.MANUAL)
        assertEquals(first.id, f.transactions.get(id)!!.accountId)
    }
}
