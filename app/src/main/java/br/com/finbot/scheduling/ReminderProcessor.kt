package br.com.finbot.scheduling

import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.NotificationStatus
import br.com.finbot.data.entity.NotificationLogEntity
import br.com.finbot.data.repository.BillRepository
import br.com.finbot.data.repository.ReminderRepository
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Entrega uma notificação local. Retorna false se não foi possível (ex.: permissão negada). */
interface Notifier {
    fun show(event: DueEvent): Boolean
}

/**
 * Verifica contas e lembretes vencidos e entrega os avisos, cada um no máximo uma vez (por `event_key`).
 * Roda no WorkManager (periódico) e ao abrir o app; o [Mutex] impede duas execuções simultâneas no processo.
 */
class ReminderProcessor(
    db: FinBotDatabase,
    private val bills: BillRepository,
    private val reminders: ReminderRepository,
    private val notifier: Notifier,
    private val zone: suspend () -> ZoneId,
    private val clock: Clock,
) {
    private val log = db.notificationLogDao()
    private val mutex = Mutex()

    data class Result(val delivered: Int = 0, val failed: Int = 0, val skipped: Int = 0)

    private enum class Outcome { DELIVERED, FAILED, SKIPPED, ALREADY_DONE }

    suspend fun run(): Result = mutex.withLock {
        val now = Instant.now(clock)
        val zone = zone()
        val today = now.atZone(zone).toLocalDate()
        val outcomes = mutableListOf<Outcome>()

        bills.ensureOccurrencesAround(today)
        val pending = bills.listPendingUntil(today.plusDays(MAX_OFFSET_DAYS)).map {
            PendingBill(it.billUuid, it.title, it.amountCents, it.dueDate, ReminderOffsets.parse(it.remindOffsetsDays), it.billCreatedAt)
        }
        val plan = DueEventPlanner.planBills(pending, now, zone)
        plan.stale.forEach { outcomes += skip(it, now) }
        plan.due.forEach { outcomes += deliver(it, now) }

        reminders.listDue(now).forEach { reminder ->
            val event = DueEventPlanner.reminderEvent(reminder.uuid, reminder.title, reminder.scheduledAt, now)
            val outcome = if (Duration.between(reminder.scheduledAt, now) > DueEventPlanner.CATCH_UP) skip(event, now) else deliver(event, now)
            outcomes += outcome
            // Falha temporária mantém o lembrete onde está para nova tentativa; nos demais casos avança.
            if (outcome != Outcome.FAILED || attempts(event.key) >= MAX_ATTEMPTS) reminders.markNotified(reminder, now, zone)
        }

        val result = Result(
            delivered = outcomes.count { it == Outcome.DELIVERED },
            failed = outcomes.count { it == Outcome.FAILED },
            skipped = outcomes.count { it == Outcome.SKIPPED },
        )
        if (result != Result()) SafeLog.i("Avisos: ${result.delivered} entregues, ${result.failed} falharam, ${result.skipped} pulados")
        result
    }

    private suspend fun attempts(key: String) = log.findByKey(key)?.attemptCount ?: 0

    private suspend fun deliver(event: DueEvent, now: Instant): Outcome {
        val existing = log.findByKey(event.key)
        if (existing != null) {
            if (existing.status != NotificationStatus.FAILED) return Outcome.ALREADY_DONE
            if (existing.attemptCount >= MAX_ATTEMPTS) return Outcome.ALREADY_DONE
        }
        val ok = runCatching { notifier.show(event) }.getOrDefault(false)
        val status = if (ok) NotificationStatus.SENT else NotificationStatus.FAILED
        val error = if (ok) null else "Notificação não exibida (permissão ou canal desativado)"
        if (existing == null) {
            log.insertIfAbsent(
                NotificationLogEntity(eventKey = event.key, status = status, attemptCount = 1, notifiedAt = if (ok) now else null, lastError = error),
            )
        } else {
            log.update(existing.copy(status = status, attemptCount = existing.attemptCount + 1, notifiedAt = if (ok) now else null, lastError = error))
        }
        return if (ok) Outcome.DELIVERED else Outcome.FAILED
    }

    private suspend fun skip(event: DueEvent, now: Instant): Outcome {
        if (log.findByKey(event.key) != null) return Outcome.ALREADY_DONE
        log.insertIfAbsent(NotificationLogEntity(eventKey = event.key, status = NotificationStatus.SKIPPED, attemptCount = 0, lastError = "Mais de 7 dias de atraso"))
        return Outcome.SKIPPED
    }

    companion object {
        const val MAX_ATTEMPTS = 3

        /** Maior antecedência de aviso permitida (dias antes do vencimento). */
        const val MAX_OFFSET_DAYS = 30L
    }
}
