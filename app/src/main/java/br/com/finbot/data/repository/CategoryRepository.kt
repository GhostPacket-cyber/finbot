package br.com.finbot.data.repository

import androidx.room.withTransaction
import br.com.finbot.data.AccountType
import br.com.finbot.data.AuditAction
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.entity.AccountEntity
import br.com.finbot.data.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.util.UUID

class DuplicateNameException(name: String) : IllegalArgumentException("Já existe uma categoria chamada \"$name\".")

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

    companion object {
        const val DEFAULT_NAME = "Carteira"
    }
}

internal fun newUuid(): String = UUID.randomUUID().toString()
