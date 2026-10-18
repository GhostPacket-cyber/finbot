package br.com.finbot.data.repository

import androidx.room.withTransaction
import br.com.finbot.data.AuditAction
import br.com.finbot.data.BillOccurrenceStatus
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.ShortMonthPolicy
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.dao.OccurrenceView
import br.com.finbot.scheduling.Recurrence
import br.com.finbot.scheduling.ReminderOffsets
import kotlinx.coroutines.flow.Flow
import br.com.finbot.data.entity.BillEntity
import br.com.finbot.data.entity.BillOccurrenceEntity
import br.com.finbot.domain.Money
import br.com.finbot.domain.TransactionDraft
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Contas a pagar. Uma conta só vira despesa realizada quando é paga, por meio de exatamente
 * um lançamento vinculado. Recorrência e lembretes completos ficam para a ETAPA 06.
 */
class BillRepository(
    private val db: FinBotDatabase,
    private val clock: Clock,
    private val transactions: TransactionRepository,
) {
    private val dao = db.billDao()
    private val categories = db.categoryDao()
    private val audit = AuditLogger(db.auditLogDao())

    suspend fun createBill(
        title: String,
        amountCents: Long,
        categoryId: Long?,
        firstDueDate: LocalDate,
        recurrenceRule: String? = null,
        shortMonthPolicy: ShortMonthPolicy = ShortMonthPolicy.LAST_DAY_OF_MONTH,
        remindOffsets: List<Int> = ReminderOffsets.DEFAULT,
    ): Long {
        val clean = validTitle(title)
        require(Money.isValidAmount(amountCents)) { "Valor inválido." }
        require(recurrenceRule == null || recurrenceRule == MONTHLY) { "Recorrência não suportada." }
        val now = Instant.now(clock)
        return db.withTransaction {
            checkCategory(categoryId)
            val bill = BillEntity(
                uuid = newUuid(),
                title = clean,
                amountCents = amountCents,
                categoryId = categoryId,
                firstDueDate = firstDueDate,
                recurrenceRule = recurrenceRule,
                shortMonthPolicy = shortMonthPolicy,
                remindOffsetsDays = ReminderOffsets.encode(remindOffsets),
                createdAt = now,
            )
            val id = dao.insertBill(bill)
            audit.record(AuditLogger.BILL, bill.uuid, AuditAction.CREATE, now, mapOf("amount_cents" to amountCents, "recurrence" to recurrenceRule))
            id
        }
    }

    /**
     * Edita título, valor, categoria, avisos e regra de meses curtos. O valor novo vale para os próximos pagamentos;
     * pagamentos já feitos mantêm o lançamento original. Vencimento e recorrência não mudam (crie outra conta).
     */
    suspend fun updateBill(
        id: Long,
        title: String,
        amountCents: Long,
        categoryId: Long?,
        shortMonthPolicy: ShortMonthPolicy,
        remindOffsets: List<Int>,
    ) {
        val clean = validTitle(title)
        require(Money.isValidAmount(amountCents)) { "Valor inválido." }
        val now = Instant.now(clock)
        db.withTransaction {
            checkCategory(categoryId)
            val current = requireNotNull(dao.getBill(id)) { "Conta inexistente" }
            dao.updateBill(
                current.copy(
                    title = clean,
                    amountCents = amountCents,
                    categoryId = categoryId,
                    shortMonthPolicy = shortMonthPolicy,
                    remindOffsetsDays = ReminderOffsets.encode(remindOffsets),
                ),
            )
            audit.record(AuditLogger.BILL, current.uuid, AuditAction.UPDATE, now, mapOf("amount_cents" to "${current.amountCents}→$amountCents"))
        }
    }

    /** Desativa a conta: some das pendências e para de gerar avisos. Pagamentos feitos continuam no histórico. */
    suspend fun deactivate(id: Long) = db.withTransaction {
        val current = requireNotNull(dao.getBill(id)) { "Conta inexistente" }
        if (!current.active) return@withTransaction
        dao.updateBill(current.copy(active = false))
        audit.record(AuditLogger.BILL, current.uuid, AuditAction.DELETE, Instant.now(clock), mapOf("active" to false))
    }

    /**
     * Cria (idempotente) as ocorrências das contas ativas na janela [hoje − 62 dias, hoje + 40 dias].
     * A janela limita o passado: uma conta criada com vencimento muito antigo não gera dezenas de atrasos.
     */
    suspend fun ensureOccurrencesAround(today: LocalDate) {
        val from = today.minusDays(PAST_WINDOW_DAYS)
        val to = today.plusDays(FUTURE_WINDOW_DAYS)
        db.withTransaction {
            dao.listActiveBills().forEach { bill ->
                Recurrence.dueDates(bill.firstDueDate, bill.recurrenceRule, bill.shortMonthPolicy, from, to)
                    .forEach { date -> dao.insertOccurrenceIfAbsent(BillOccurrenceEntity(uuid = newUuid(), billId = bill.id, dueDate = date)) }
            }
        }
    }

    suspend fun listPendingUntil(until: LocalDate): List<OccurrenceView> = dao.listPendingUntil(until)
    fun observePendingUntil(until: LocalDate): Flow<List<OccurrenceView>> = dao.observePendingUntil(until)
    fun observePaidSince(since: Instant): Flow<List<OccurrenceView>> = dao.observePaidSince(since)
    fun observeActiveBills(): Flow<List<BillEntity>> = dao.observeActiveBills()
    suspend fun getBill(id: Long): BillEntity? = dao.getBill(id)

    private fun validTitle(title: String): String {
        val clean = title.trim()
        require(clean.length in 1..MAX_TITLE) { "O título deve ter de 1 a $MAX_TITLE caracteres." }
        require(clean.none { it.isISOControl() }) { "O título contém caracteres inválidos." }
        return clean
    }

    private suspend fun checkCategory(categoryId: Long?) {
        categoryId ?: return
        val category = requireNotNull(categories.getById(categoryId)) { "Categoria inexistente." }
        require(category.kind == TransactionKind.EXPENSE) { "Contas usam categorias de despesa." }
    }

    /** Idempotente: retorna a ocorrência existente para (conta, vencimento) ou cria uma nova. */
    suspend fun ensureOccurrence(billId: Long, dueDate: LocalDate): BillOccurrenceEntity = db.withTransaction {
        dao.insertOccurrenceIfAbsent(BillOccurrenceEntity(uuid = newUuid(), billId = billId, dueDate = dueDate))
        requireNotNull(dao.findOccurrence(billId, dueDate))
    }

    /**
     * Marca como paga criando um único lançamento de despesa. Chamadas repetidas devolvem o mesmo lançamento,
     * sem criar outro. Retorna o id do lançamento.
     */
    suspend fun markPaid(occurrenceId: Long, paidAt: Instant, accountId: Long? = null): Long = db.withTransaction {
        val occurrence = requireNotNull(dao.getOccurrence(occurrenceId)) { "Ocorrência inexistente" }
        if (occurrence.status == BillOccurrenceStatus.PAID) {
            return@withTransaction requireNotNull(occurrence.linkedTransactionId) { "Ocorrência paga sem lançamento" }
        }
        check(occurrence.status == BillOccurrenceStatus.PENDING) { "Ocorrência ignorada não pode ser paga" }
        val bill = requireNotNull(dao.getBill(occurrence.billId))
        val transactionId = transactions.create(
            TransactionDraft(
                kind = TransactionKind.EXPENSE,
                amountCents = bill.amountCents,
                description = bill.title,
                categoryId = bill.categoryId,
                accountId = accountId,
                occurredAt = paidAt,
            ),
            source = TransactionSource.BILL,
        )
        dao.updateOccurrence(
            occurrence.copy(status = BillOccurrenceStatus.PAID, paidAt = paidAt, linkedTransactionId = transactionId),
        )
        audit.record(AuditLogger.BILL_OCCURRENCE, occurrence.uuid, AuditAction.PAY, Instant.now(clock), mapOf("transaction_id" to transactionId))
        transactionId
    }

    /** Desfaz o pagamento: o lançamento é excluído logicamente e a ocorrência volta a pendente. */
    suspend fun unmarkPaid(occurrenceId: Long) = db.withTransaction {
        val occurrence = requireNotNull(dao.getOccurrence(occurrenceId)) { "Ocorrência inexistente" }
        val transactionId = occurrence.linkedTransactionId ?: return@withTransaction
        transactions.softDelete(transactionId)
    }

    suspend fun getOccurrence(id: Long): BillOccurrenceEntity? = dao.getOccurrence(id)

    companion object {
        const val MAX_TITLE = 80
        const val MONTHLY = Recurrence.MONTHLY
        const val PAST_WINDOW_DAYS = 62L
        const val FUTURE_WINDOW_DAYS = 40L
    }
}
