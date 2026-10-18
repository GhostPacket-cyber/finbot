package br.com.finbot.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import br.com.finbot.data.PendingActionStatus
import br.com.finbot.data.entity.AuditLogEntity
import br.com.finbot.data.entity.BillEntity
import br.com.finbot.data.entity.BillOccurrenceEntity
import br.com.finbot.data.entity.BudgetEntity
import br.com.finbot.data.entity.ChatMessageEntity
import br.com.finbot.data.entity.ImportBatchEntity
import br.com.finbot.data.entity.NotificationLogEntity
import br.com.finbot.data.entity.PendingActionEntity
import br.com.finbot.data.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

@Dao
interface BillDao {
    @Insert
    suspend fun insertBill(bill: BillEntity): Long

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getBill(id: Long): BillEntity?

    /** Retorna -1 se a ocorrência (bill_id, due_date) já existir. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrenceIfAbsent(occurrence: BillOccurrenceEntity): Long

    @Update
    suspend fun updateOccurrence(occurrence: BillOccurrenceEntity)

    @Query("SELECT * FROM bill_occurrences WHERE id = :id")
    suspend fun getOccurrence(id: Long): BillOccurrenceEntity?

    @Query("SELECT * FROM bill_occurrences WHERE bill_id = :billId AND due_date = :dueDate")
    suspend fun findOccurrence(billId: Long, dueDate: LocalDate): BillOccurrenceEntity?

    @Query("SELECT * FROM bill_occurrences WHERE linked_transaction_id = :transactionId")
    suspend fun findOccurrenceByTransaction(transactionId: Long): BillOccurrenceEntity?

    @Query("SELECT * FROM bill_occurrences WHERE status = 'PENDING' ORDER BY due_date")
    suspend fun listPendingOccurrences(): List<BillOccurrenceEntity>
}

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY scheduled_at")
    suspend fun listEnabled(): List<ReminderEntity>
}

@Dao
interface NotificationLogDao {
    /** Retorna -1 se o evento já foi registrado: o chamador não deve notificar de novo. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: NotificationLogEntity): Long

    @Query("SELECT * FROM notification_log WHERE event_key = :eventKey")
    suspend fun findByKey(eventKey: String): NotificationLogEntity?
}

@Dao
interface PendingActionDao {
    @Insert
    suspend fun insert(action: PendingActionEntity): Long

    @Query("SELECT * FROM pending_actions WHERE id = :id")
    suspend fun getById(id: Long): PendingActionEntity?

    /**
     * Transição atômica a partir de PENDING, válida só antes de expirar.
     * Retorna 1 na primeira chamada e 0 nas seguintes, garantindo uso único.
     */
    @Query(
        """
        UPDATE pending_actions SET status = :newStatus
        WHERE id = :id AND status = 'PENDING' AND expires_at > :now
        """,
    )
    suspend fun resolveIfPending(id: Long, newStatus: PendingActionStatus, now: Instant): Int

    @Query("DELETE FROM pending_actions WHERE expires_at <= :now OR status != 'PENDING'")
    suspend fun deleteExpiredOrResolved(now: Instant): Int
}

@Dao
interface BudgetDao {
    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE category_id = :categoryId AND month = :month")
    suspend fun find(categoryId: Long, month: String): BudgetEntity?
}

@Dao
interface ImportBatchDao {
    @Insert
    suspend fun insert(batch: ImportBatchEntity): Long

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun getById(id: Long): ImportBatchEntity?
}

@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(entry: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log WHERE entity = :entity AND entity_uuid = :entityUuid ORDER BY id")
    suspend fun listFor(entity: String, entityUuid: String): List<AuditLogEntity>
}

@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ChatMessageEntity>>

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}
