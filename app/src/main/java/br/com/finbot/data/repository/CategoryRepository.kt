package br.com.finbot.data.repository

import androidx.room.withTransaction
import br.com.finbot.data.AccountType
import br.com.finbot.data.AuditAction
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.dao.CategoryWithUsage
import br.com.finbot.data.entity.CategoryEntity
import br.com.finbot.data.entity.BudgetEntity
import br.com.finbot.domain.Money
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.util.UUID

class DuplicateNameException(name: String) : IllegalArgumentException("Já existe um item chamado \"$name\".")

class CategoryRepository(private val db: FinBotDatabase, private val clock: Clock) {
    private val dao = db.categoryDao()
    private val audit = AuditLogger(db.auditLogDao())

    /** Cria as categorias padrão na primeira execução. Retorna quantas foram criadas (0 se já existiam). */
    suspend fun ensureDefaults(): Int = db.withTransaction {
        if (dao.count() > 0) return@withTransaction 0
        DEFAULTS.forEach { (name, kind) -> dao.insert(CategoryEntity(uuid = newUuid(), name = name, kind = kind)) }
        DEFAULTS.size
    }

    suspend fun create(name: String, kind: TransactionKind): Long {
        val clean = validName(name)
        return db.withTransaction {
            if (dao.findByName(clean, kind) != null) throw DuplicateNameException(clean)
            val entity = CategoryEntity(uuid = newUuid(), name = clean, kind = kind)
            val id = dao.insert(entity)
            audit.record(AuditLogger.CATEGORY, entity.uuid, AuditAction.CREATE, now(), mapOf("name" to clean, "kind" to kind))
            id
        }
    }

    /** Categorias não são apagadas (lançamentos as referenciam); são arquivadas. */
    suspend fun archive(id: Long) = db.withTransaction {
        val current = requireNotNull(dao.getById(id)) { "Categoria inexistente" }
        if (current.archived) return@withTransaction
        dao.update(current.copy(archived = true))
        audit.record(AuditLogger.CATEGORY, current.uuid, AuditAction.UPDATE, now(), mapOf("archived" to true))
    }

    suspend fun rename(id: Long, newName: String) {
        val clean = validName(newName)
        db.withTransaction {
            val current = requireNotNull(dao.getById(id)) { "Categoria inexistente" }
            if (current.name == clean) return@withTransaction
            dao.findByName(clean, current.kind)?.let { other -> if (other.id != id) throw DuplicateNameException(clean) }
            dao.update(current.copy(name = clean))
            audit.record(AuditLogger.CATEGORY, current.uuid, AuditAction.UPDATE, now(), mapOf("name" to "alterado"))
        }
    }

    suspend fun unarchive(id: Long) = db.withTransaction {
        val current = requireNotNull(dao.getById(id)) { "Categoria inexistente" }
        if (!current.archived) return@withTransaction
        dao.update(current.copy(archived = false))
        audit.record(AuditLogger.CATEGORY, current.uuid, AuditAction.UPDATE, now(), mapOf("archived" to false))
    }

    /**
     * Mescla [sourceId] em [targetId] (mesmo tipo): lançamentos, contas e orçamentos passam para o destino
     * e a origem é arquivada. Tudo numa transação. Retorna quantos lançamentos foram remapeados.
     */
    suspend fun merge(sourceId: Long, targetId: Long): Int {
        require(sourceId != targetId) { "Escolha outra categoria de destino." }
        val now = now()
        return db.withTransaction {
            val source = requireNotNull(dao.getById(sourceId)) { "Categoria de origem inexistente" }
            val target = requireNotNull(dao.getById(targetId)) { "Categoria de destino inexistente" }
            require(source.kind == target.kind) { "Só é possível mesclar categorias do mesmo tipo." }
            require(!target.archived) { "A categoria de destino está arquivada." }
            val moved = db.transactionDao().remapCategory(sourceId, targetId, now)
            db.billDao().remapCategory(sourceId, targetId)
            db.budgetDao().deleteConflictingForMerge(sourceId, targetId)
            db.budgetDao().remapCategory(sourceId, targetId)
            dao.update(source.copy(archived = true))
            audit.record(
                AuditLogger.CATEGORY, source.uuid, AuditAction.MERGE, now,
                mapOf("target_uuid" to target.uuid, "transactions" to moved),
            )
            moved
        }
    }

    /**
     * Orçamento mensal de uma categoria de despesa, válido a partir de [fromMonth] (vale para os meses seguintes
     * até ser trocado). [limitCents] null remove o orçamento a partir desse mês.
     */
    suspend fun setMonthlyBudget(categoryId: Long, limitCents: Long?, fromMonth: YearMonth) {
        require(limitCents == null || Money.isValidAmount(limitCents)) { "Informe um valor maior que zero." }
        db.withTransaction {
            val category = requireNotNull(dao.getById(categoryId)) { "Categoria inexistente." }
            require(category.kind == TransactionKind.EXPENSE) { "Orçamento só para categorias de despesa." }
            val budgets = db.budgetDao()
            val month = fromMonth.toString()
            val current = budgets.find(categoryId, month)
            budgets.upsert(
                BudgetEntity(id = current?.id ?: 0, uuid = current?.uuid ?: newUuid(), categoryId = categoryId, month = month, limitCents = limitCents ?: 0),
            )
            audit.record(AuditLogger.CATEGORY, category.uuid, AuditAction.UPDATE, now(), mapOf("budget_month" to month, "budget_cents" to (limitCents ?: 0)))
        }
    }

    fun observeBudgets(month: YearMonth): Flow<Map<Long, Long>> =
        db.budgetDao().observeEffectiveForMonth(month.toString()).map { list -> list.filter { it.limitCents > 0 }.associate { it.categoryId to it.limitCents } }

    fun observeAllWithUsage(): Flow<List<CategoryWithUsage>> = dao.observeAllWithUsage()

    fun observeAll(): Flow<List<CategoryEntity>> = dao.observeAll()

    suspend fun findByName(name: String, kind: TransactionKind): CategoryEntity? = dao.findByName(name.trim(), kind)

    suspend fun listActive(kind: TransactionKind): List<CategoryEntity> = dao.listActive(kind)

    fun observeActive(kind: TransactionKind): Flow<List<CategoryEntity>> = dao.observeActive(kind)

    private fun validName(name: String): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        require(clean.length in 1..MAX_NAME) { "O nome da categoria deve ter de 1 a $MAX_NAME caracteres." }
        require(clean.none { it.isISOControl() }) { "O nome contém caracteres inválidos." }
        return clean
    }

    private fun now(): Instant = Instant.now(clock)

    companion object {
        const val MAX_NAME = 40

        val DEFAULTS: List<Pair<String, TransactionKind>> = listOf(
            "Alimentação" to TransactionKind.EXPENSE,
            "Mercado" to TransactionKind.EXPENSE,
            "Transporte" to TransactionKind.EXPENSE,
            "Moradia" to TransactionKind.EXPENSE,
            "Saúde" to TransactionKind.EXPENSE,
            "Lazer" to TransactionKind.EXPENSE,
            "Educação" to TransactionKind.EXPENSE,
            "Assinaturas" to TransactionKind.EXPENSE,
            "Outros" to TransactionKind.EXPENSE,
            "Salário" to TransactionKind.INCOME,
            "Outros" to TransactionKind.INCOME,
        )
    }
}

class AccountRepository(private val db: FinBotDatabase) {
    private val dao = db.accountDao()

    /** Garante a conta "Carteira" e a retorna. */
    suspend fun ensureDefault(): AccountEntity = db.withTransaction {
        dao.getDefault() ?: run {
            val entity = AccountEntity(uuid = newUuid(), name = DEFAULT_NAME, type = AccountType.CASH)
            entity.copy(id = dao.insert(entity))
        }
    }

    suspend fun listActive(): List<AccountEntity> = dao.listActive()

    fun observeAll(): Flow<List<AccountEntity>> = dao.observeAll()

    suspend fun create(name: String, type: AccountType): Long {
        val clean = validName(name)
        return db.withTransaction {
            if (dao.findByName(clean) != null) throw DuplicateNameException(clean)
            dao.insert(AccountEntity(uuid = newUuid(), name = clean, type = type))
        }
    }

    suspend fun rename(id: Long, newName: String) {
        val clean = validName(newName)
        db.withTransaction {
            val current = requireNotNull(dao.getById(id)) { "Conta inexistente" }
            dao.findByName(clean)?.let { other -> if (other.id != id) throw DuplicateNameException(clean) }
            dao.update(current.copy(name = clean))
        }
    }

    /** A última conta ativa não pode ser arquivada (todo lançamento precisa de uma). */
    suspend fun setArchived(id: Long, archived: Boolean) = db.withTransaction {
        val current = requireNotNull(dao.getById(id)) { "Conta inexistente" }
        if (current.archived == archived) return@withTransaction
        if (archived) require(dao.countActive() > 1) { "Mantenha ao menos uma conta ativa." }
        dao.update(current.copy(archived = archived))
    }

    private fun validName(name: String): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        require(clean.length in 1..CategoryRepository.MAX_NAME) { "O nome deve ter de 1 a ${CategoryRepository.MAX_NAME} caracteres." }
        require(clean.none { it.isISOControl() }) { "O nome contém caracteres inválidos." }
        return clean
    }

    companion object {
        const val DEFAULT_NAME = "Carteira"
    }
}

internal fun newUuid(): String = UUID.randomUUID().toString()
