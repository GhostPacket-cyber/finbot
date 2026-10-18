package br.com.finbot.data.repository

import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.entity.ReminderEntity
import br.com.finbot.scheduling.ReminderRepeat
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Lembretes pessoais (não financeiros), separados das contas. [ReminderEntity.scheduledAt] é sempre a próxima ocorrência. */
class ReminderRepository(db: FinBotDatabase, private val clock: Clock) {
    private val dao = db.reminderDao()

    suspend fun create(title: String, at: LocalDateTime, repeat: ReminderRepeat, zone: ZoneId): Long {
        val clean = title.trim()
        require(clean.length in 1..MAX_TITLE) { "O lembrete deve ter de 1 a $MAX_TITLE caracteres." }
        require(clean.none { it.isISOControl() }) { "O texto contém caracteres inválidos." }
        val instant = at.atZone(zone).toInstant()
        require(repeat != ReminderRepeat.Once || instant.isAfter(Instant.now(clock))) { "Escolha uma data e hora no futuro." }
        return dao.insert(ReminderEntity(uuid = newUuid(), title = clean, scheduledAt = instant, recurrenceRule = repeat.encode()))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean, zone: ZoneId) {
        val current = requireNotNull(dao.getById(id)) { "Lembrete inexistente" }
        var updated = current.copy(enabled = enabled)
        // Ao reativar um lembrete recorrente parado há tempo, pula para a próxima ocorrência futura.
        if (enabled && !current.scheduledAt.isAfter(Instant.now(clock))) {
            val next = nextOccurrence(current, Instant.now(clock), zone) ?: return
            updated = updated.copy(scheduledAt = next)
        }
        dao.update(updated)
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun listDue(now: Instant): List<ReminderEntity> = dao.listDue(now)

    fun observeAll(): Flow<List<ReminderEntity>> = dao.observeAll()

    /** Após avisar: recorrente avança para a próxima ocorrência futura; único é desativado. */
    suspend fun markNotified(reminder: ReminderEntity, now: Instant, zone: ZoneId) {
        val next = nextOccurrence(reminder, now, zone)
        dao.update(
            reminder.copy(
                scheduledAt = next ?: reminder.scheduledAt,
                enabled = next != null,
                lastNotifiedAt = now,
            ),
        )
    }

    private fun nextOccurrence(reminder: ReminderEntity, now: Instant, zone: ZoneId): Instant? {
        val repeat = ReminderRepeat.decode(reminder.recurrenceRule)
        val current = reminder.scheduledAt.atZone(zone).toLocalDateTime()
        return repeat.nextAfter(current, now.atZone(zone).toLocalDateTime())?.atZone(zone)?.toInstant()
    }

    companion object {
        const val MAX_TITLE = 80
    }
}
