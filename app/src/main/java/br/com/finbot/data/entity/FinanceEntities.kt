package br.com.finbot.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import br.com.finbot.data.AccountType
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import java.time.Instant

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["name", "kind"], unique = true),
    ],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val kind: TransactionKind,
    /** Cor ARGB opcional para gráficos. */
    val color: Long? = null,
    val icon: String? = null,
    val archived: Boolean = false,
)

@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["name"], unique = true),
    ],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val type: AccountType,
    val archived: Boolean = false,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        // SQLite trata NULLs como distintos: só chaves preenchidas (importação/notificação) são deduplicadas.
        Index(value = ["dedup_key"], unique = true),
        Index(value = ["status", "occurred_at"]),
        Index(value = ["category_id"]),
        Index(value = ["account_id"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val kind: TransactionKind,
    /** Sempre positivo; o sinal vem de [kind]. */
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val currency: String = "BRL",
    val description: String,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    val source: TransactionSource,
    val status: TransactionStatus,
    @ColumnInfo(name = "dedup_key") val dedupKey: String? = null,
    @ColumnInfo(name = "external_reference") val externalReference: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null,
    /** Observação livre do usuário. Adicionada no schema v2. */
    val note: String? = null,
)
