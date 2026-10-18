package br.com.finbot.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.entity.CategoryEntity
import br.com.finbot.data.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// Regra comum a todas as consultas financeiras: só status CONFIRMED e não excluídos (deleted_at IS NULL).

data class KindTotals(
    @ColumnInfo(name = "income_cents") val incomeCents: Long,
    @ColumnInfo(name = "expense_cents") val expenseCents: Long,
    val count: Int,
)

data class CategoryTotal(
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "total_cents") val totalCents: Long,
    val count: Int,
)

data class TransactionWithCategory(
    @Embedded val transaction: TransactionEntity,
    @ColumnInfo(name = "category_name") val categoryName: String?,
)

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name AND kind = :kind LIMIT 1")
    suspend fun findByName(name: String, kind: TransactionKind): CategoryEntity?

    @Query("SELECT * FROM categories WHERE kind = :kind AND archived = 0 ORDER BY name")
    fun observeActive(kind: TransactionKind): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE kind = :kind AND archived = 0 ORDER BY name")
    suspend fun listActive(kind: TransactionKind): List<CategoryEntity>
}

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    /** Conta padrão: a primeira ativa criada. */
    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY id LIMIT 1")
    suspend fun getDefault(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY name")
    suspend fun listActive(): List<AccountEntity>
}

@Dao
interface TransactionDao {
    /** Lança SQLiteConstraintException se o dedup_key já existir. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE dedup_key = :dedupKey LIMIT 1")
    suspend fun findByDedupKey(dedupKey: String): TransactionEntity?

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN kind = 'INCOME' THEN amount_cents ELSE -amount_cents END), 0)
        FROM transactions WHERE status = 'CONFIRMED' AND deleted_at IS NULL
        """,
    )
    suspend fun balanceCents(): Long

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN kind = 'INCOME' THEN amount_cents ELSE -amount_cents END), 0)
        FROM transactions WHERE status = 'CONFIRMED' AND deleted_at IS NULL
        """,
    )
    fun observeBalanceCents(): Flow<Long>

    /** Intervalo semiaberto [start, end). */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN kind = 'INCOME' THEN amount_cents END), 0) AS income_cents,
            COALESCE(SUM(CASE WHEN kind = 'EXPENSE' THEN amount_cents END), 0) AS expense_cents,
            COUNT(*) AS count
        FROM transactions
        WHERE status = 'CONFIRMED' AND deleted_at IS NULL
          AND occurred_at >= :start AND occurred_at < :end
        """,
    )
    suspend fun totalsBetween(start: Instant, end: Instant): KindTotals

    @Query(
        """
        SELECT t.category_id AS category_id, c.name AS category_name,
               SUM(t.amount_cents) AS total_cents, COUNT(*) AS count
        FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.status = 'CONFIRMED' AND t.deleted_at IS NULL AND t.kind = :kind
          AND t.occurred_at >= :start AND t.occurred_at < :end
        GROUP BY t.category_id
        ORDER BY total_cents DESC, category_name
        """,
    )
    suspend fun totalsByCategoryBetween(kind: TransactionKind, start: Instant, end: Instant): List<CategoryTotal>

    @Query(
        """
        SELECT * FROM transactions
        WHERE status = 'CONFIRMED' AND deleted_at IS NULL
          AND occurred_at >= :start AND occurred_at < :end
        ORDER BY occurred_at DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listConfirmedBetween(start: Instant, end: Instant, limit: Int, offset: Int): List<TransactionEntity>

    @Query(
        """
        SELECT t.*, c.name AS category_name FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.status = 'CONFIRMED' AND t.deleted_at IS NULL
        ORDER BY t.occurred_at DESC, t.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listRecentConfirmed(limit: Int, offset: Int): List<TransactionWithCategory>

    /** Último lançamento ativo criado pela origem informada (usado por /desfazer). */
    @Query(
        """
        SELECT * FROM transactions
        WHERE source = :source AND status = 'CONFIRMED' AND deleted_at IS NULL
        ORDER BY created_at DESC, id DESC LIMIT 1
        """,
    )
    suspend fun lastActiveBySource(source: TransactionSource): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE status = 'PENDING_REVIEW' AND deleted_at IS NULL ORDER BY occurred_at DESC")
    suspend fun listPendingReview(): List<TransactionEntity>
}
