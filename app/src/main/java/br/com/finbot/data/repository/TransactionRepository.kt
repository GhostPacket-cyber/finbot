package br.com.finbot.data.repository

import androidx.room.withTransaction
import br.com.finbot.data.AuditAction
import br.com.finbot.data.BillOccurrenceStatus
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.data.dao.KindTotals
import br.com.finbot.data.dao.TransactionRow
import br.com.finbot.data.entity.TransactionEntity
import br.com.finbot.domain.FilterQuery
import kotlinx.coroutines.flow.Flow
import br.com.finbot.domain.InvalidTransactionException
import br.com.finbot.domain.TransactionDraft
import br.com.finbot.domain.TransactionRules
import java.time.Clock
import java.time.Instant

/** CRUD de lançamentos com validação, auditoria e exclusão lógica. Toda escrita é transacional. */
class TransactionRepository(
    private val db: FinBotDatabase,
    private val clock: Clock,
    private val accounts: AccountRepository = AccountRepository(db),
) {
    private val dao = db.transactionDao()
    private val categories = db.categoryDao()
    private val bills = db.billDao()
    private val audit = AuditLogger(db.auditLogDao())

    suspend fun create(
        draft: TransactionDraft,
        source: TransactionSource,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        dedupKey: String? = null,
        externalReference: String? = null,
    ): Long {
        val now = now()
        TransactionRules.requireValid(draft, now)
        require(status != TransactionStatus.DISCARDED) { "Não se cria lançamento já descartado" }
        return db.withTransaction {
            checkCategory(draft)
            val accountId = draft.accountId ?: accounts.ensureDefault().id
            val entity = TransactionEntity(
                uuid = newUuid(),
                kind = draft.kind,
                amountCents = draft.amountCents,
                description = draft.description.trim(),
                categoryId = draft.categoryId,
                accountId = accountId,
                occurredAt = draft.occurredAt,
                createdAt = now,
                updatedAt = now,
                source = source,
                status = status,
                dedupKey = dedupKey,
                externalReference = externalReference,
                note = draft.note?.trim()?.ifEmpty { null },
            )
            val id = dao.insert(entity)
            audit.record(
                AuditLogger.TRANSACTION, entity.uuid, AuditAction.CREATE, now,
                mapOf("kind" to entity.kind, "amount_cents" to entity.amountCents, "source" to source, "status" to status),
            )
            id
        }
    }

    /**
     * Para importações e notificações: cria somente se [dedupKey] ainda não existir.
     * Retorna null quando é duplicata (inclusive de lançamentos excluídos ou descartados, que não voltam).
     */
    suspend fun createIfNew(
        draft: TransactionDraft,
        source: TransactionSource,
        dedupKey: String,
        status: TransactionStatus = TransactionStatus.PENDING_REVIEW,
    ): Long? = db.withTransaction {
        if (dao.findByDedupKey(dedupKey) != null) null else create(draft, source, status, dedupKey)
    }

    suspend fun update(id: Long, draft: TransactionDraft) {
        val now = now()
        TransactionRules.requireValid(draft, now)
        db.withTransaction {
            val current = requireActive(id)
            checkCategory(draft)
            val updated = current.copy(
                kind = draft.kind,
                amountCents = draft.amountCents,
                description = draft.description.trim(),
                categoryId = draft.categoryId,
                accountId = draft.accountId ?: current.accountId,
                occurredAt = draft.occurredAt,
                note = draft.note?.trim()?.ifEmpty { null },
                updatedAt = now,
            )
            if (updated == current.copy(updatedAt = now)) return@withTransaction
            dao.update(updated)
            audit.record(AuditLogger.TRANSACTION, current.uuid, AuditAction.UPDATE, now, diff(current, updated))
        }
    }

    /** Exclusão lógica. Se o lançamento pagava uma conta, a ocorrência volta a ficar pendente. */
    suspend fun softDelete(id: Long) {
        val now = now()
        db.withTransaction {
            val current = requireActive(id)
            dao.update(current.copy(deletedAt = now, updatedAt = now))
            bills.findOccurrenceByTransaction(id)?.let { occurrence ->
                bills.updateOccurrence(
                    occurrence.copy(status = BillOccurrenceStatus.PENDING, paidAt = null, linkedTransactionId = null),
                )
                audit.record(AuditLogger.BILL_OCCURRENCE, occurrence.uuid, AuditAction.UNPAY, now, mapOf("transaction_uuid" to current.uuid))
            }
            audit.record(AuditLogger.TRANSACTION, current.uuid, AuditAction.DELETE, now, mapOf("amount_cents" to current.amountCents))
        }
    }

    /** Desfaz uma exclusão lógica ("Desfazer" do snackbar). Não refaz vínculo com conta paga. */
    suspend fun restore(id: Long) {
        val now = now()
        db.withTransaction {
            val current = requireNotNull(dao.getById(id)) { "Lançamento inexistente" }
            if (current.deletedAt == null) return@withTransaction
            dao.update(current.copy(deletedAt = null, updatedAt = now))
            audit.record(AuditLogger.TRANSACTION, current.uuid, AuditAction.RESTORE, now, mapOf("amount_cents" to current.amountCents))
        }
    }

    /** Confirma vários itens da caixa de revisão: todos ou nenhum. */
    suspend fun confirmAll(ids: Collection<Long>) = db.withTransaction { ids.forEach { confirm(it) } }

    /** Descarta vários itens da caixa de revisão: todos ou nenhum. */
    suspend fun discardAll(ids: Collection<Long>) = db.withTransaction { ids.forEach { discard(it) } }

    fun observeFiltered(q: FilterQuery, limit: Int): Flow<List<TransactionRow>> =
        dao.observeFiltered(q.start, q.end, q.kind?.name, q.categoryId, q.accountId, q.source?.name, q.likePattern, limit)

    fun observeFilteredTotals(q: FilterQuery): Flow<KindTotals> =
        dao.observeFilteredTotals(q.start, q.end, q.kind?.name, q.categoryId, q.accountId, q.source?.name, q.likePattern)

    fun observePendingReview(): Flow<List<TransactionRow>> = dao.observePendingReview()

    fun observePendingReviewCount(): Flow<Int> = dao.observePendingReviewCount()

    /** PENDING_REVIEW → CONFIRMED (passa a contar no saldo). */
    suspend fun confirm(id: Long) = changeStatus(id, TransactionStatus.CONFIRMED, AuditAction.CONFIRM)

    /** PENDING_REVIEW → DISCARDED (nunca conta e não volta por deduplicação). */
    suspend fun discard(id: Long) = changeStatus(id, TransactionStatus.DISCARDED, AuditAction.DISCARD)

    suspend fun get(id: Long): TransactionEntity? = dao.getById(id)

    suspend fun listPendingReview(): List<TransactionEntity> = dao.listPendingReview()

    private suspend fun changeStatus(id: Long, target: TransactionStatus, action: AuditAction) {
        val now = now()
        db.withTransaction {
            val current = requireActive(id)
            check(current.status == TransactionStatus.PENDING_REVIEW) { "Só lançamentos em revisão podem ser confirmados ou descartados" }
            dao.update(current.copy(status = target, updatedAt = now))
            audit.record(AuditLogger.TRANSACTION, current.uuid, action, now, mapOf("status" to target))
        }
    }

    private suspend fun requireActive(id: Long): TransactionEntity {
        val current = requireNotNull(dao.getById(id)) { "Lançamento inexistente" }
        check(current.deletedAt == null) { "Lançamento excluído" }
        return current
    }

    private suspend fun checkCategory(draft: TransactionDraft) {
        val categoryId = draft.categoryId ?: return
        val category = categories.getById(categoryId) ?: throw InvalidTransactionException(listOf("Categoria inexistente."))
        if (category.kind != draft.kind) throw InvalidTransactionException(listOf("A categoria não corresponde ao tipo do lançamento."))
        if (category.archived) throw InvalidTransactionException(listOf("A categoria está arquivada."))
    }

    private fun diff(old: TransactionEntity, new: TransactionEntity): Map<String, Any?> = buildMap {
        if (old.kind != new.kind) put("kind", "${old.kind}→${new.kind}")
        if (old.amountCents != new.amountCents) put("amount_cents", "${old.amountCents}→${new.amountCents}")
        if (old.description != new.description) put("description", "alterada")
        if (old.categoryId != new.categoryId) put("category_id", "${old.categoryId}→${new.categoryId}")
        if (old.accountId != new.accountId) put("account_id", "${old.accountId}→${new.accountId}")
        if (old.occurredAt != new.occurredAt) put("occurred_at", "${old.occurredAt}→${new.occurredAt}")
        if (old.note != new.note) put("note", "alterada")
    }

    private fun now(): Instant = Instant.now(clock)
}
