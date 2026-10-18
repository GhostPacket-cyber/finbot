package br.com.finbot.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import br.com.finbot.data.BillOccurrenceStatus
import br.com.finbot.data.NotificationStatus
import br.com.finbot.data.ShortMonthPolicy
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["uuid"], unique = true), Index(value = ["category_id"])],
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val title: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "first_due_date") val firstDueDate: LocalDate,
    /** null = conta única; "MONTHLY" = mensal. Regras completas na ETAPA 06. */
    @ColumnInfo(name = "recurrence_rule") val recurrenceRule: String?,
    @ColumnInfo(name = "short_month_policy") val shortMonthPolicy: ShortMonthPolicy = ShortMonthPolicy.LAST_DAY_OF_MONTH,
    /** Dias relativos ao vencimento, separados por vírgula (ex.: "3,0,-1"). */
    @ColumnInfo(name = "remind_offsets_days") val remindOffsetsDays: String = "3,0",
    val active: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)

@Entity(
    tableName = "bill_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["bill_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["linked_transaction_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["bill_id", "due_date"], unique = true),
        // Um lançamento só pode pagar uma ocorrência: impede dupla contagem.
        Index(value = ["linked_transaction_id"], unique = true),
    ],
)
data class BillOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "bill_id") val billId: Long,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate,
    val status: BillOccurrenceStatus = BillOccurrenceStatus.PENDING,
    @ColumnInfo(name = "paid_at") val paidAt: Instant? = null,
    @ColumnInfo(name = "linked_transaction_id") val linkedTransactionId: Long? = null,
)

@Entity(tableName = "reminders", indices = [Index(value = ["uuid"], unique = true)])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val title: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Instant,
    @ColumnInfo(name = "recurrence_rule") val recurrenceRule: String?,
    val enabled: Boolean = true,
    @ColumnInfo(name = "last_notified_at") val lastNotifiedAt: Instant? = null,
)

/** Idempotência de notificações locais: um [eventKey] é notificado no máximo uma vez. */
@Entity(tableName = "notification_log", indices = [Index(value = ["event_key"], unique = true)])
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_key") val eventKey: String,
    val status: NotificationStatus,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 1,
    @ColumnInfo(name = "notified_at") val notifiedAt: Instant? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)
