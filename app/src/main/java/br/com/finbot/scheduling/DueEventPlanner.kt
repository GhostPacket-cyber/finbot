package br.com.finbot.scheduling

import br.com.finbot.domain.Money
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Para onde o toque na notificação leva. */
enum class NotificationTarget { BILLS }

/** Aviso pronto para entregar. [key] é estável e define a idempotência (uma entrega por chave). */
data class DueEvent(
    val key: String,
    val notifyAt: Instant,
    val title: String,
    val body: String,
    val target: NotificationTarget = NotificationTarget.BILLS,
)

/** Ocorrência pendente de uma conta ativa, com o que é preciso para planejar avisos. */
data class PendingBill(
    val billUuid: String,
    val title: String,
    val amountCents: Long,
    val dueDate: LocalDate,
    val offsets: List<Int>,
    /** Cadastro da conta: avisos planejados antes disso nunca são entregues. */
    val createdAt: Instant = Instant.EPOCH,
)

data class Plan(val due: List<DueEvent>, val stale: List<DueEvent>)

/**
 * Decide quais avisos já deveriam ter sido entregues. Puro: recebe tudo por parâmetro.
 * Avisos atrasados até [CATCH_UP] são entregues com contexto; os mais antigos são marcados como pulados.
 */
object DueEventPlanner {
    val NOTIFY_TIME: LocalTime = LocalTime.of(9, 0)
    val CATCH_UP: Duration = Duration.ofDays(7)
    private val LATE_NOTE_THRESHOLD: Duration = Duration.ofMinutes(30)
    private val DATE = DateTimeFormatter.ofPattern("dd/MM")

    fun billKey(billUuid: String, dueDate: LocalDate, offset: Int) = "bill:$billUuid:$dueDate:$offset"

    fun planBills(bills: List<PendingBill>, now: Instant, zone: ZoneId): Plan {
        val today = now.atZone(zone).toLocalDate()
        val due = mutableListOf<DueEvent>()
        val skipped = mutableListOf<DueEvent>()
        bills.forEach { bill ->
            val events = bill.offsets.map { offset ->
                val notifyAt = bill.dueDate.minusDays(offset.toLong()).atTime(NOTIFY_TIME).atZone(zone).toInstant()
                DueEvent(
                    key = billKey(bill.billUuid, bill.dueDate, offset),
                    notifyAt = notifyAt,
                    title = "Conta: ${bill.title}",
                    body = billBody(bill, today) + lateNote(notifyAt, now),
                )
            }.filter { !it.notifyAt.isAfter(now) }

            // Planejado antes de a conta existir, ou atrasado demais: registra como pulado.
            val (invalid, candidates) = events.partition { it.notifyAt < bill.createdAt || Duration.between(it.notifyAt, now) > CATCH_UP }
            skipped += invalid
            // Vários avisos da mesma ocorrência vencidos juntos (app parado): só o mais recente é entregue.
            val latest = candidates.maxByOrNull { it.notifyAt }
            if (latest != null) due += latest
            skipped += candidates.filter { it != latest }
        }
        return Plan(due.sortedBy { it.notifyAt }, skipped)
    }

    /** O texto descreve a situação no momento da entrega (não no horário planejado). */
    fun billBody(bill: PendingBill, today: LocalDate): String {
        val value = Money.format(bill.amountCents)
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, bill.dueDate)
        return when {
            days > 1 -> "$value vence em $days dias (${bill.dueDate.format(DATE)})."
            days == 1L -> "$value vence amanhã (${bill.dueDate.format(DATE)})."
            days == 0L -> "$value vence hoje."
            days == -1L -> "$value venceu ontem (${bill.dueDate.format(DATE)}) e ainda não foi paga."
            else -> "$value está atrasada há ${-days} dias (venceu em ${bill.dueDate.format(DATE)})."
        }
    }

    fun reminderKey(reminderUuid: String, scheduledAt: Instant) = "reminder:$reminderUuid:${scheduledAt.toEpochMilli()}"

    fun reminderEvent(uuid: String, title: String, scheduledAt: Instant, now: Instant): DueEvent =
        DueEvent(
            key = reminderKey(uuid, scheduledAt),
            notifyAt = scheduledAt,
            title = "Lembrete",
            body = title + lateNote(scheduledAt, now),
        )

    /** "Aviso com atraso de 2 h": deixa claro quando o Android atrasou a verificação. */
    fun lateNote(notifyAt: Instant, now: Instant): String {
        val late = Duration.between(notifyAt, now)
        if (late < LATE_NOTE_THRESHOLD) return ""
        val text = when {
            late.toDays() >= 1 -> "${late.toDays()} dia(s)"
            late.toHours() >= 1 -> "${late.toHours()} h"
            else -> "${late.toMinutes()} min"
        }
        return " (aviso com atraso de $text)"
    }
}
