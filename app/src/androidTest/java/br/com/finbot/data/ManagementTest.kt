package br.com.finbot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.data.entity.BudgetEntity
import br.com.finbot.data.repository.AuditLogger
import br.com.finbot.data.repository.DuplicateNameException
import br.com.finbot.domain.FormResult
import br.com.finbot.domain.PeriodPreset
import br.com.finbot.domain.TransactionDraft
import br.com.finbot.domain.TransactionFilter
import br.com.finbot.domain.TransactionForm
import br.com.finbot.domain.TransactionFormInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ManagementTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 10, 17)
    private lateinit var f: TestFinance

    @Before
    fun setUp() = runTest {
        f = TestFinance() // 17/10/2026 12:00 local
        f.seed()
    }

    @After
    fun tearDown() = f.close()

    private suspend fun add(
        kind: TransactionKind, cents: Long, desc: String, category: String?, at: String,
        source: TransactionSource = TransactionSource.MANUAL, accountId: Long? = null, note: String? = null,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
    ): Long = f.transactions.create(
        TransactionDraft(kind, cents, desc, category?.let { f.categoryId(it, kind) }, accountId, Instant.parse(at), note),
        source, status,
    )

    private suspend fun list(filter: TransactionFilter, limit: Int = 100) =
        f.transactions.observeFiltered(filter.toQuery(today, zone), limit).first()

    private suspend fun totals(filter: TransactionFilter) = f.transactions.observeFilteredTotals(filter.toQuery(today, zone)).first()

    @Test
    fun filtrosBatemComCalculoManual() = runTest {
        val nubank = f.accounts.create("Nubank", AccountType.CHECKING)
        add(TransactionKind.EXPENSE, 4_590, "Mercado Extra", "Mercado", "2026-10-10T15:00:00Z")
        add(TransactionKind.EXPENSE, 1_200, "Uber", "Transporte", "2026-10-12T15:00:00Z", TransactionSource.CHAT, nubank)
        add(TransactionKind.INCOME, 250_000, "Salário", "Salário", "2026-10-05T15:00:00Z", accountId = nubank)
        add(TransactionKind.EXPENSE, 9_990, "Internet", "Moradia", "2026-09-28T15:00:00Z", TransactionSource.BILL)
        add(TransactionKind.EXPENSE, 3_000, "Mercado do bairro", "Mercado", "2026-10-15T15:00:00Z", note = "feira")
        add(TransactionKind.EXPENSE, 777, "Sugestão", "Outros", "2026-10-16T15:00:00Z", status = TransactionStatus.PENDING_REVIEW)
        val deleted = add(TransactionKind.EXPENSE, 555, "Apagado", "Outros", "2026-10-16T15:00:00Z")
        f.transactions.softDelete(deleted)

        // Este mês: só confirmados e não excluídos de outubro.
        val month = TransactionFilter()
        assertEquals(listOf("Mercado do bairro", "Uber", "Mercado Extra", "Salário"), list(month).map { it.transaction.description })
        totals(month).let {
            assertEquals(250_000L, it.incomeCents)
            assertEquals(4_590L + 1_200 + 3_000, it.expenseCents)
            assertEquals(4, it.count)
        }
        assertEquals(5, list(TransactionFilter(period = PeriodPreset.ALL)).size)
        assertEquals(listOf("Internet"), list(TransactionFilter(period = PeriodPreset.LAST_MONTH)).map { it.transaction.description })
        assertEquals(3, list(month.copy(kind = TransactionKind.EXPENSE)).size)
        assertEquals(7_590L, totals(month.copy(categoryId = f.categoryId("Mercado", TransactionKind.EXPENSE))).expenseCents)
        assertEquals(setOf("Uber", "Salário"), list(month.copy(accountId = nubank)).map { it.transaction.description }.toSet())
        assertEquals(listOf("Uber"), list(month.copy(source = TransactionSource.CHAT)).map { it.transaction.description })
        // Busca na descrição (sem diferenciar maiúsculas) e na observação.
        assertEquals(2, list(month.copy(search = "MERCADO")).size)
        assertEquals(listOf("Mercado do bairro"), list(month.copy(search = "feira")).map { it.transaction.description })
        // Nomes de categoria e conta vêm junto.
        list(month.copy(source = TransactionSource.CHAT)).single().let {
            assertEquals("Transporte", it.categoryName)
            assertEquals("Nubank", it.accountName)
        }
    }

    @Test
    fun buscaTrataCuringasComoTextoEPaginaPorLimite() = runTest {
        add(TransactionKind.EXPENSE, 100, "Desconto 50% loja", null, "2026-10-10T15:00:00Z")
        add(TransactionKind.EXPENSE, 100, "Desconto 50 reais", null, "2026-10-10T15:00:00Z")
        add(TransactionKind.EXPENSE, 100, "a_b", null, "2026-10-10T15:00:00Z")
        add(TransactionKind.EXPENSE, 100, "axb", null, "2026-10-10T15:00:00Z")
        assertEquals(listOf("Desconto 50% loja"), list(TransactionFilter(search = "50%")).map { it.transaction.description })
        assertEquals(listOf("a_b"), list(TransactionFilter(search = "a_b")).map { it.transaction.description })
        assertEquals(2, list(TransactionFilter(), limit = 2).size)
        assertEquals(4, totals(TransactionFilter()).count)
    }

    @Test
    fun excluirERestaurarSaoAuditados() = runTest {
        val id = add(TransactionKind.EXPENSE, 4_500, "Almoço", "Alimentação", "2026-10-17T15:00:00Z")
        f.transactions.softDelete(id)
        assertEquals(0L, f.summaries.balanceCents())
        assertNotNull(f.transactions.get(id)!!.deletedAt)
        f.transactions.restore(id)
        assertEquals(-4_500L, f.summaries.balanceCents())
        assertNull(f.transactions.get(id)!!.deletedAt)
        val actions = f.db.auditLogDao().listFor(AuditLogger.TRANSACTION, f.transactions.get(id)!!.uuid).map { it.action }
        assertEquals(listOf(AuditAction.CREATE, AuditAction.DELETE, AuditAction.RESTORE), actions)
    }

    @Test
    fun corrigirLancamentoCriadoPeloChat() = runTest {
        f.assistant.send("gastei 45 no mercado")
        val card = f.assistant.observe().first().firstNotNullOf { it.card }
        f.assistant.confirm(card.actionId)
        val row = list(TransactionFilter()).single()
        assertEquals(TransactionSource.CHAT, row.transaction.source)

        // O formulário de edição corrige valor, categoria e observação.
        val input = TransactionFormInput(
            kind = TransactionKind.EXPENSE, amount = "54,00", description = "Mercado Extra",
            categoryId = f.categoryId("Alimentação", TransactionKind.EXPENSE), accountId = row.transaction.accountId,
            date = "17/10/2026", note = "corrigido",
        )
        val draft = (TransactionForm.parse(input, zone, f.clock.current, row.transaction.occurredAt) as FormResult.Valid).draft
        f.transactions.update(row.transaction.id, draft)

        val fixed = list(TransactionFilter()).single()
        assertEquals(5_400L, fixed.transaction.amountCents)
        assertEquals("Alimentação", fixed.categoryName)
        assertEquals("corrigido", fixed.transaction.note)
        assertEquals("a origem continua sendo o chat", TransactionSource.CHAT, fixed.transaction.source)
        assertEquals(row.transaction.occurredAt, fixed.transaction.occurredAt)
        assertEquals(-5_400L, f.summaries.balanceCents())
        val audit = f.db.auditLogDao().listFor(AuditLogger.TRANSACTION, fixed.transaction.uuid)
        assertEquals(AuditAction.UPDATE, audit.last().action)
        assertTrue(audit.last().summaryJson.contains("4500→5400"))
    }

    @Test
    fun revisaoEmLoteEAtomica() = runTest {
        val a = add(TransactionKind.EXPENSE, 1_000, "Sugestão A", null, "2026-10-16T15:00:00Z", status = TransactionStatus.PENDING_REVIEW)
        val b = add(TransactionKind.EXPENSE, 2_000, "Sugestão B", null, "2026-10-16T15:00:00Z", status = TransactionStatus.PENDING_REVIEW)
        val confirmed = add(TransactionKind.EXPENSE, 300, "Já confirmado", null, "2026-10-16T15:00:00Z")
        assertEquals(2, f.transactions.observePendingReview().first().size)

        // Um item inválido (já confirmado) no lote: nada muda.
        runCatching { f.transactions.confirmAll(listOf(a, confirmed)) }.onSuccess { fail("deveria falhar") }
        assertEquals(TransactionStatus.PENDING_REVIEW, f.transactions.get(a)!!.status)
        assertEquals(-300L, f.summaries.balanceCents())

        f.transactions.confirmAll(listOf(a))
        f.transactions.discardAll(listOf(b))
        assertEquals(-1_300L, f.summaries.balanceCents())
        assertEquals(TransactionStatus.DISCARDED, f.transactions.get(b)!!.status)
        assertEquals(0, f.transactions.observePendingReviewCount().first())
    }

    @Test
    fun mesclarCategoriasRemapeiaTudoEArquivaAOrigem() = runTest {
        val lazer = f.categoryId("Lazer", TransactionKind.EXPENSE)
        val outros = f.categoryId("Outros", TransactionKind.EXPENSE)
        add(TransactionKind.EXPENSE, 5_000, "Cinema", "Lazer", "2026-10-10T15:00:00Z")
        add(TransactionKind.EXPENSE, 7_000, "Show", "Lazer", "2026-10-11T15:00:00Z")
        val bill = f.bills.createBill("Clube", 9_000, lazer, LocalDate.of(2026, 10, 20))
        f.db.budgetDao().upsert(BudgetEntity(uuid = "b1", categoryId = lazer, month = "2026-10", limitCents = 10_000))
        f.db.budgetDao().upsert(BudgetEntity(uuid = "b2", categoryId = lazer, month = "2026-11", limitCents = 10_000))
        f.db.budgetDao().upsert(BudgetEntity(uuid = "b3", categoryId = outros, month = "2026-10", limitCents = 20_000))

        assertEquals(2, f.categories.merge(lazer, outros))

        assertEquals(12_000L, totals(TransactionFilter(categoryId = outros)).expenseCents)
        assertEquals(0, totals(TransactionFilter(categoryId = lazer)).count)
        assertEquals(outros, f.db.billDao().getBill(bill)!!.categoryId)
        assertEquals(20_000L, f.db.budgetDao().find(outros, "2026-10")!!.limitCents) // destino prevalece
        assertNotNull(f.db.budgetDao().find(outros, "2026-11"))
        assertTrue(f.db.categoryDao().getById(lazer)!!.archived)
        assertEquals(AuditAction.MERGE, f.db.auditLogDao().listFor(AuditLogger.CATEGORY, f.db.categoryDao().getById(lazer)!!.uuid).last().action)

        // Tipos diferentes não se mesclam.
        runCatching { f.categories.merge(f.categoryId("Salário", TransactionKind.INCOME), outros) }
            .onSuccess { fail("tipos diferentes") }
    }

    @Test
    fun renomearArquivarEContas() = runTest {
        val mercado = f.categoryId("Mercado", TransactionKind.EXPENSE)
        f.categories.rename(mercado, "Supermercado")
        assertEquals("Supermercado", f.db.categoryDao().getById(mercado)!!.name)
        try {
            f.categories.rename(mercado, "lazer") // NOCASE: colide com "Lazer"
            fail("nome duplicado")
        } catch (_: DuplicateNameException) {
        }
        f.categories.archive(mercado)
        f.categories.unarchive(mercado)
        assertTrue(!f.db.categoryDao().getById(mercado)!!.archived)

        val carteira = f.accounts.ensureDefault()
        try {
            f.accounts.setArchived(carteira.id, true)
            fail("última conta ativa")
        } catch (_: IllegalArgumentException) {
        }
        val nubank = f.accounts.create("Nubank", AccountType.CHECKING)
        try {
            f.accounts.create("nubank", AccountType.SAVINGS)
            fail("conta duplicada")
        } catch (_: DuplicateNameException) {
        }
        f.accounts.setArchived(carteira.id, true)
        assertEquals(listOf(nubank), f.accounts.listActive().map { it.id })
    }

}
