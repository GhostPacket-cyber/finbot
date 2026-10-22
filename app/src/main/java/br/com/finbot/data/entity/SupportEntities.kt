package br.com.finbot.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import br.com.finbot.data.ActionOrigin
import br.com.finbot.data.AuditAction
import br.com.finbot.data.ChatRole
import br.com.finbot.data.ImportSource
import br.com.finbot.data.PendingActionStatus
import java.time.Instant

/** Ação aguardando confirmação do usuário (cartão do chat, lote de importação). Vale uma vez e expira. */
@Entity(tableName = "pending_actions", indices = [Index(value = ["status", "expires_at"])])
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    val origin: ActionOrigin,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "expires_at") val expiresAt: Instant,
    val status: PendingActionStatus = PendingActionStatus.PENDING,
)

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["uuid"], unique = true), Index(value = ["category_id", "month"], unique = true)],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    /** "yyyy-MM". */
    val month: String,
    @ColumnInfo(name = "limit_cents") val limitCents: Long,
)

@Entity(tableName = "import_batches", indices = [Index(value = ["uuid"], unique = true)])
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val source: ImportSource,
    /** Hash do nome do arquivo: identifica reimportações sem guardar o nome. */
    @ColumnInfo(name = "file_name_hash") val fileNameHash: String,
    @ColumnInfo(name = "imported_at") val importedAt: Instant,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    /** Conta do app que recebeu os lançamentos (v4). */
    @ColumnInfo(name = "account_id") val accountId: Long? = null,
    /** Conta do extrato já mascarada ("••••1234"); o número completo nunca é gravado (v4). */
    @ColumnInfo(name = "masked_account") val maskedAccount: String? = null,
    /** Itens do arquivo ignorados por já existirem (v4). */
    @ColumnInfo(name = "skipped_count", defaultValue = "0") val skippedCount: Int = 0,
    /** Quando o lote foi desfeito (v4). */
    @ColumnInfo(name = "undone_at") val undoneAt: Instant? = null,
)

/** Mapeamento de CSV salvo por banco (v4). Índices de coluna começam em 0. */
@Entity(tableName = "csv_presets", indices = [Index(value = ["name"], unique = true)])
data class CsvPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val separator: String,
    @ColumnInfo(name = "skip_lines") val skipLines: Int,
    @ColumnInfo(name = "skip_footer_lines") val skipFooterLines: Int,
    @ColumnInfo(name = "date_column") val dateColumn: Int,
    @ColumnInfo(name = "description_column") val descriptionColumn: Int,
    @ColumnInfo(name = "amount_column") val amountColumn: Int?,
    @ColumnInfo(name = "debit_column") val debitColumn: Int?,
    @ColumnInfo(name = "credit_column") val creditColumn: Int?,
    @ColumnInfo(name = "date_format") val dateFormat: String,
    @ColumnInfo(name = "invert_sign") val invertSign: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

/** Trilha de alterações. [summaryJson] traz só campos alterados, nunca conteúdo bruto de chat ou áudio. */
@Entity(tableName = "audit_log", indices = [Index(value = ["entity", "entity_uuid"])])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entity: String,
    @ColumnInfo(name = "entity_uuid") val entityUuid: String,
    val action: AuditAction,
    @ColumnInfo(name = "changed_at") val changedAt: Instant,
    @ColumnInfo(name = "summary_json") val summaryJson: String,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = PendingActionEntity::class,
            parentColumns = ["id"],
            childColumns = ["related_action_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["created_at"]), Index(value = ["related_action_id"])],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: ChatRole,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "related_action_id") val relatedActionId: Long? = null,
    /** Comando enviado ao tocar no botão da mensagem (ex.: "/gastos 2"). Schema v3. */
    @ColumnInfo(name = "quick_reply") val quickReply: String? = null,
    @ColumnInfo(name = "quick_reply_label") val quickReplyLabel: String? = null,
)
