package br.com.finbot.data.repository

import androidx.room.withTransaction
import br.com.finbot.data.ActionOrigin
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.PendingActionStatus
import br.com.finbot.data.entity.PendingActionEntity
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Ações que aguardam Confirmar/Cancelar. Cada ação é resolvida no máximo uma vez e expira após o TTL.
 */
class PendingActionRepository(private val db: FinBotDatabase, private val clock: Clock) {
    private val dao = db.pendingActionDao()

    suspend fun create(payloadJson: String, origin: ActionOrigin, ttl: Duration = DEFAULT_TTL): Long {
        require(!ttl.isNegative && !ttl.isZero) { "TTL inválido" }
        val now = Instant.now(clock)
        return dao.insert(PendingActionEntity(payloadJson = payloadJson, origin = origin, createdAt = now, expiresAt = now.plus(ttl)))
    }

    /**
     * Confirma e executa [onConfirmed] na mesma transação. Retorna null se a ação já foi resolvida ou expirou.
     * Se [onConfirmed] falhar, tudo é revertido e a ação continua pendente.
     */
    suspend fun <T> confirm(id: Long, onConfirmed: suspend (PendingActionEntity) -> T): T? = db.withTransaction {
        val action = dao.getById(id) ?: return@withTransaction null
        if (dao.resolveIfPending(id, PendingActionStatus.CONFIRMED, Instant.now(clock)) != 1) return@withTransaction null
        onConfirmed(action)
    }

    /** Retorna false se a ação já tinha sido resolvida ou expirou. */
    suspend fun cancel(id: Long): Boolean =
        dao.resolveIfPending(id, PendingActionStatus.CANCELLED, Instant.now(clock)) == 1

    suspend fun get(id: Long): PendingActionEntity? = dao.getById(id)

    /** Remove ações criadas há mais de [retention]; as recentes ficam para o chat exibir o status. */
    suspend fun purge(retention: Duration = RETENTION): Int = dao.deleteCreatedBefore(Instant.now(clock).minus(retention))

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofMinutes(15)
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
