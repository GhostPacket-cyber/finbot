package br.com.finbot.notifications

import br.com.finbot.assistant.CategorySuggester
import br.com.finbot.config.AppConfig
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.data.repository.ImportRepository
import br.com.finbot.data.repository.TransactionRepository
import br.com.finbot.domain.InvalidTransactionException
import br.com.finbot.domain.TransactionDraft
import br.com.finbot.importing.DedupKeys
import br.com.finbot.security.SafeLog
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Aviso discreto do FinBot sobre uma nova sugestão. */
fun interface SuggestionNotifier {
    fun notifySuggestion(transactionId: Long, event: BankNotificationEvent)
}

enum class IngestResult { DISABLED, NOT_ALLOWED, NO_MATCH, DUPLICATE, INVALID, CREATED }

/**
 * Decide se uma notificação pode ser lida e, se sim, transforma em sugestão (`PENDING_REVIEW`).
 * O conteúdo só é acessado ([readTexts]) depois de passar pelo portão (recurso ligado + pacote permitido).
 * Nada do texto bruto é guardado ou registrado em log.
 */
class BankNotificationIngestor(
    private val config: suspend () -> AppConfig,
    private val zone: suspend () -> ZoneId,
    private val transactions: TransactionRepository,
    private val imports: ImportRepository,
    private val notifier: SuggestionNotifier,
    private val ownPackage: String,
) {
    suspend fun ingest(packageName: String, postedAt: Instant, readTexts: () -> List<String>): IngestResult {
        val cfg = config()
        if (!cfg.bankNotificationsEnabled) return IngestResult.DISABLED
        if (!isAllowed(packageName, cfg)) return IngestResult.NOT_ALLOWED

        val event = BankNotificationParser.parse(readTexts(), postedAt, zone()) ?: return IngestResult.NO_MATCH
        val draft = TransactionDraft(
            kind = event.kind,
            amountCents = event.amountCents,
            description = event.description,
            categoryId = imports.suggestCategory(event.kind, event.counterpart),
            accountId = null,
            occurredAt = event.occurredAt,
        )
        val id = try {
            transactions.createIfNew(draft, TransactionSource.BANK_NOTIFICATION, dedupKey(packageName, event), TransactionStatus.PENDING_REVIEW)
        } catch (e: InvalidTransactionException) {
            SafeLog.w("Sugestão de notificação inválida: ${e.errors.size} erro(s)")
            return IngestResult.INVALID
        } ?: return IngestResult.DUPLICATE
        SafeLog.i("Notificação de banco virou sugestão para revisão")
        notifier.notifySuggestion(id, event)
        return IngestResult.CREATED
    }

    fun isAllowed(packageName: String, cfg: AppConfig): Boolean =
        packageName != ownPackage && packageName in cfg.bankNotificationPackages

    companion object {
        /** hash(pacote + valor com sinal + estabelecimento normalizado + minuto). */
        fun dedupKey(packageName: String, event: BankNotificationEvent): String {
            val signed = if (event.kind == TransactionKind.EXPENSE) -event.amountCents else event.amountCents
            val minute = event.occurredAt.truncatedTo(ChronoUnit.MINUTES).epochSecond / 60
            return "notif:" + DedupKeys.sha256("$packageName|$signed|${CategorySuggester.normalize(event.counterpart)}|$minute")
        }
    }
}
