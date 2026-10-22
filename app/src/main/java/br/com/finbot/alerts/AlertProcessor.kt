package br.com.finbot.alerts

import androidx.room.withTransaction
import br.com.finbot.config.AppConfig
import br.com.finbot.data.ChatRole
import br.com.finbot.data.NotificationStatus
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.entity.ChatMessageEntity
import br.com.finbot.data.entity.NotificationLogEntity
import br.com.finbot.domain.Periods
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** Mostra o alerta como notificação do sistema. Retorna false se não foi possível. */
fun interface AlertNotifier {
    fun show(alert: Alert, text: String): Boolean
}

/**
 * Calcula os alertas ligados e entrega cada um **uma única vez** (chave em `notification_log`):
 * mensagem do assistente no chat e, se permitido, notificação local. Funciona sem IA.
 * Roda no WorkManager (junto dos avisos de contas) e ao abrir o app.
 */
class AlertProcessor(
    private val db: FinBotDatabase,
    private val clock: Clock,
    private val zone: suspend () -> ZoneId,
    private val config: suspend () -> AppConfig,
    private val notifier: AlertNotifier,
    private val rewriter: AlertRewriter? = null,
) {
    private val transactions = db.transactionDao()
    private val log = db.notificationLogDao()
    private val chat = db.chatMessageDao()
    private val mutex = Mutex()

    suspend fun run(): List<Alert> = mutex.withLock {
        val settings = config().alerts
        val now = Instant.now(clock)
        val zone = zone()
        val delivered = mutableListOf<Alert>()
        for (alert in compute(settings, now, zone)) {
            if (log.findByKey(alert.key) != null) continue
            val text = rewrite(alert, settings.aiRewrite) ?: alert.text
            val claimed = db.withTransaction {
                if (log.insertIfAbsent(NotificationLogEntity(eventKey = alert.key, status = NotificationStatus.SENT, notifiedAt = now)) == -1L) {
                    return@withTransaction false
                }
                alert.alsoMarks.forEach { log.insertIfAbsent(NotificationLogEntity(eventKey = it, status = NotificationStatus.SKIPPED, notifiedAt = now)) }
                chat.insert(ChatMessageEntity(role = ChatRole.ASSISTANT, text = text, createdAt = now))
                true
            }
            if (!claimed) continue
            if (settings.notify) notifier.show(alert, text)
            delivered += alert
        }
        if (delivered.isNotEmpty()) SafeLog.i("Alertas entregues: ${delivered.size}")
        delivered
    }

    private suspend fun rewrite(alert: Alert, enabled: Boolean): String? {
        if (!enabled || rewriter == null) return null
        return try {
            rewriter.rewrite(alert.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.w("Reescrita de alerta indisponível", e)
            null
        }
    }

    suspend fun compute(settings: br.com.finbot.config.AlertSettings, now: Instant, zone: ZoneId): List<Alert> {
        val month = YearMonth.from(now.atZone(zone))
        val names = db.categoryDao().observeAll().first().associate { it.id to it.name }
        val alerts = mutableListOf<Alert>()

        if (settings.budget || settings.unusual) {
            val perMonth = (0L..3L).associate { back ->
                val m = month.minusMonths(back)
                val range = Periods.month(m, zone)
                m to transactions.totalsByCategoryBetween(TransactionKind.EXPENSE, range.start, range.end)
                    .mapNotNull { t -> t.categoryId?.let { it to t.totalCents } }.toMap()
            }
            if (settings.budget) {
                val limits = db.budgetDao().effectiveForMonth(month.toString()).associate { it.categoryId to it.limitCents }
                alerts += AlertRules.budget(month, perMonth.getValue(month), limits, names)
            }
            if (settings.unusual) {
                val byCategory = perMonth.flatMap { (m, totals) -> totals.map { (cat, v) -> Triple(cat, m, v) } }
                    .groupBy({ it.first }, { it.second to it.third }).mapValues { (_, v) -> v.toMap() }
                alerts += AlertRules.unusualSpending(month, byCategory, names, settings.unusualThresholdPercent)
            }
        }

        if (settings.duplicate || settings.subscription) {
            // Dois meses inteiros para trás cobrem "meses consecutivos" e a janela de duplicadas.
            val start = Periods.month(month.minusMonths(2), zone).start
            val items = transactions.listActiveExpensesBetween(start, now.plusSeconds(1)).map {
                SpendItem(it.uuid, it.amountCents, it.description, it.categoryId, it.occurredAt, it.status == TransactionStatus.CONFIRMED) to it.source
            }
            if (settings.duplicate) alerts += AlertRules.duplicateCharges(items.map { it.first }, settings.duplicateWindowHours, now, zone)
            if (settings.subscription) {
                val bills = db.billDao().listActiveBills().map { AlertRules.KnownBill(it.title, it.amountCents) }
                // Pagamentos de contas já cadastradas não são "assinatura nova".
                alerts += AlertRules.subscriptions(items.filter { it.second != TransactionSource.BILL }.map { it.first }, bills, now, zone)
            }
        }

        val security = config().security
        AlertRules.backupReminder(security.lastExportAt, transactions.firstCreatedAt(), security.backupReminderDays, now)?.let { alerts += it }

        if (settings.weekly) {
            val time = LocalTime.of(settings.weeklyMinuteOfDay / 60, settings.weeklyMinuteOfDay % 60)
            AlertRules.weeklyKey(now, zone, settings.weeklyDay, time)?.let { key ->
                if (log.findByKey(key) == null) alerts += AlertRules.weeklySummary(key, weeklyFacts(now, zone, settings, time))
            }
        }
        return alerts
    }

    private suspend fun weeklyFacts(now: Instant, zone: ZoneId, settings: br.com.finbot.config.AlertSettings, time: LocalTime): AlertRules.WeeklyFacts {
        val slotDate = AlertRules.lastWeeklySlot(now, zone, settings.weeklyDay, time).atZone(zone).toLocalDate()
        val start = slotDate.minusDays(7)
        val end = slotDate.minusDays(1)
        val range = Periods.day(start, zone).start to Periods.day(end, zone).end
        val totals = transactions.totalsBetween(range.first, range.second)
        val top = transactions.totalsByCategoryBetween(TransactionKind.EXPENSE, range.first, range.second).firstOrNull()
        val bills = db.billDao().listPendingUntil(slotDate.plusDays(6)).filter { !it.dueDate.isBefore(slotDate) }
        return AlertRules.WeeklyFacts(
            start = start,
            end = end,
            incomeCents = totals.incomeCents,
            expenseCents = totals.expenseCents,
            topCategory = top?.categoryName ?: top?.let { "Sem categoria" },
            topCategoryCents = top?.totalCents ?: 0,
            pendingReview = transactions.listPendingReview().size,
            billsNext7Days = bills.size,
            billsNext7DaysCents = bills.sumOf { it.amountCents },
        )
    }
}
