package br.com.finbot.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import br.com.finbot.data.BillOccurrenceStatus
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

/** Ocorrência com os dados da conta (para tela, avisos e pagamento). */
data class OccurrenceView(
    @ColumnInfo(name = "occurrence_id") val occurrenceId: Long,
    @ColumnInfo(name = "bill_id") val billId: Long,
    @ColumnInfo(name = "bill_uuid") val billUuid: String,
    val title: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate,
    val status: BillOccurrenceStatus,
    @ColumnInfo(name = "paid_at") val paidAt: Instant?,
    @ColumnInfo(name = "recurrence_rule") val recurrenceRule: String?,
    @ColumnInfo(name = "remind_offsets_days") val remindOffsetsDays: String,
    @ColumnInfo(name = "bill_created_at") val billCreatedAt: Instant,
)

data class BillStatusTotal(
    val status: BillOccurrenceStatus,
    val count: Int,
    @ColumnInfo(name = "total_cents") val totalCents: Long,
)

data class BillDueRow(
    @ColumnInfo(name = "occurrence_id") val occurrenceId: Long,
    val title: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate,
)

data class ChatRow(
    @Embedded val message: ChatMessageEntity,
    @ColumnInfo(name = "action_status") val actionStatus: PendingActionStatus?,
    @ColumnInfo(name = "action_payload") val actionPayload: String?,
    @ColumnInfo(name = "action_expires_at") val actionExpiresAt: Instant?,
)

@Dao
interface BillDao {
    @Insert
    suspend fun insertBill(bill: BillEntity): Long

    @Update
    suspend fun updateBill(bill: BillEntity)

    @Query("SELECT * FROM bills WHERE active = 1")
    suspend fun listActiveBills(): List<BillEntity>

    /** Pendentes de contas ativas com vencimento até [until] (inclui atrasadas). */
    @Query(
        """
        SELECT $OCCURRENCE_COLUMNS FROM bill_occurrences o
        JOIN bills b ON b.id = o.bill_id LEFT JOIN categories c ON c.id = b.category_id
        WHERE o.status = 'PENDING' AND b.active = 1 AND o.due_date <= :until
        ORDER BY o.due_date, b.title
        """,
    )
    suspend fun listPendingUntil(until: LocalDate): List<OccurrenceView>

    @Query(
        """
        SELECT $OCCURRENCE_COLUMNS FROM bill_occurrences o
        JOIN bills b ON b.id = o.bill_id LEFT JOIN categories c ON c.id = b.category_id
        WHERE o.status = 'PENDING' AND b.active = 1 AND o.due_date <= :until
        ORDER BY o.due_date, b.title
        """,
    )
    fun observePendingUntil(until: LocalDate): Flow<List<OccurrenceView>>

    @Query(
        """
        SELECT $OCCURRENCE_COLUMNS FROM bill_occurrences o
        JOIN bills b ON b.id = o.bill_id LEFT JOIN categories c ON c.id = b.category_id
        WHERE o.status = 'PAID' AND o.paid_at >= :since
        ORDER BY o.paid_at DESC
        """,
    )
    fun observePaidSince(since: Instant): Flow<List<OccurrenceView>>

    /**
     * Contas com vencimento em [from, to] por situação. Pagas usam o valor do lançamento (o que de fato saiu);
     * pendentes, o valor atual da conta, e só de contas ativas.
     */
    @Query(
        """
        SELECT o.status AS status, COUNT(*) AS count,
               COALESCE(SUM(CASE WHEN o.status = 'PAID' THEN COALESCE(t.amount_cents, b.amount_cents) ELSE b.amount_cents END), 0) AS total_cents
        FROM bill_occurrences o
        JOIN bills b ON b.id = o.bill_id
        LEFT JOIN transactions t ON t.id = o.linked_transaction_id
        WHERE o.due_date >= :from AND o.due_date <= :to
          AND (o.status = 'PAID' OR (o.status = 'PENDING' AND b.active = 1))
        GROUP BY o.status
        """,
    )
    suspend fun totalsByStatusBetween(from: LocalDate, to: LocalDate): List<BillStatusTotal>

    @Query("SELECT * FROM bills WHERE active = 1 ORDER BY title")
    fun observeActiveBills(): Flow<List<BillEntity>>

    @Query("UPDATE bills SET category_id = :target WHERE category_id = :source")
    suspend fun remapCategory(source: Long, target: Long): Int

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

    @Query(
        """
        SELECT o.id AS occurrence_id, b.title AS title, b.amount_cents AS amount_cents, o.due_date AS due_date
        FROM bill_occurrences o JOIN bills b ON b.id = o.bill_id
        WHERE o.status = 'PENDING' AND b.active = 1
        ORDER BY o.due_date LIMIT :limit
        """,
    )
    suspend fun listPendingDue(limit: Int): List<BillDueRow>
}

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY scheduled_at")
    suspend fun listEnabled(): List<ReminderEntity>

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE enabled = 1 AND scheduled_at <= :now ORDER BY scheduled_at")
    suspend fun listDue(now: Instant): List<ReminderEntity>

    @Query("SELECT * FROM reminders ORDER BY enabled DESC, scheduled_at")
    fun observeAll(): Flow<List<ReminderEntity>>
}

@Dao
interface NotificationLogDao {
    /** Retorna -1 se o evento já foi registrado: o chamador não deve notificar de novo. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: NotificationLogEntity): Long

    @Query("SELECT * FROM notification_log WHERE event_key = :eventKey")
    suspend fun findByKey(eventKey: String): NotificationLogEntity?

    @Update
    suspend fun update(entry: NotificationLogEntity)
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

    /** Retenção: ações (resolvidas ou não) criadas antes de [cutoff]. Recentes ficam para exibir o status no chat. */
    @Query("DELETE FROM pending_actions WHERE created_at < :cutoff")
    suspend fun deleteCreatedBefore(cutoff: Instant): Int
}

@Dao
interface BudgetDao {
    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE category_id = :categoryId AND month = :month")
    suspend fun find(categoryId: Long, month: String): BudgetEntity?

    /** Ao mesclar: o orçamento da categoria de destino prevalece no mesmo mês; os demais migram. */
    @Query("DELETE FROM budgets WHERE category_id = :source AND month IN (SELECT month FROM budgets WHERE category_id = :target)")
    suspend fun deleteConflictingForMerge(source: Long, target: Long): Int

    @Query("UPDATE budgets SET category_id = :target WHERE category_id = :source")
    suspend fun remapCategory(source: Long, target: Long): Int
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

    /** Mensagens com o estado atual da ação vinculada (para desenhar o cartão de confirmação). */
    @Query(
        """
        SELECT m.*, a.status AS action_status, a.payload_json AS action_payload, a.expires_at AS action_expires_at
        FROM chat_messages m LEFT JOIN pending_actions a ON a.id = m.related_action_id
        ORDER BY m.created_at DESC, m.id DESC
        LIMIT :limit
        """,
    )
    fun observeRecentWithActions(limit: Int): Flow<List<ChatRow>>

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}

/** Colunas de [OccurrenceView] (constante de compilação para uso em @Query). */
const val OCCURRENCE_COLUMNS = """
    o.id AS occurrence_id, o.bill_id AS bill_id, b.uuid AS bill_uuid, b.title AS title, b.amount_cents AS amount_cents,
    c.name AS category_name, o.due_date AS due_date, o.status AS status, o.paid_at AS paid_at,
    b.recurrence_rule AS recurrence_rule, b.remind_offsets_days AS remind_offsets_days, b.created_at AS bill_created_at
"""
