package br.com.finbot.assistant

import br.com.finbot.config.ConfigRepository
import br.com.finbot.data.ActionOrigin
import br.com.finbot.data.ChatRole
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.PendingActionStatus
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.dao.ChatRow
import br.com.finbot.data.entity.ChatMessageEntity
import br.com.finbot.data.repository.CategoryRepository
import br.com.finbot.data.repository.PendingActionRepository
import br.com.finbot.data.repository.SummaryRepository
import br.com.finbot.data.repository.TransactionRepository
import br.com.finbot.domain.InvalidTransactionException
import br.com.finbot.domain.TransactionDraft
import br.com.finbot.domain.TransactionRules
import br.com.finbot.domain.ValidationResult
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

enum class CardStatus { PENDING, CONFIRMED, CANCELLED, EXPIRED }

data class ActionCard(val actionId: Long, val payload: ActionPayload?, val status: CardStatus)

data class ChatItem(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val createdAt: Instant,
    val card: ActionCard?,
    val quickReply: String?,
    val quickReplyLabel: String?,
)

/**
 * Orquestra o chat sem IA: interpreta a mensagem, cria cartões de confirmação e responde a consultas.
 * Nenhuma escrita financeira acontece fora de [confirm]/[confirmEdited], e cada cartão vale uma única vez.
 */
class ChatAssistant(
    db: FinBotDatabase,
    private val transactions: TransactionRepository,
    private val summaries: SummaryRepository,
    private val categories: CategoryRepository,
    private val pending: PendingActionRepository,
    private val config: ConfigRepository,
    private val clock: Clock,
) {
    private val chat = db.chatMessageDao()
    private val transactionDao = db.transactionDao()
    private val billDao = db.billDao()
    private val reminderDao = db.reminderDao()

    fun observe(limit: Int = HISTORY_LIMIT): Flow<List<ChatItem>> =
        chat.observeRecentWithActions(limit).map { rows -> rows.map(::toItem) }

    suspend fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        chat.insert(ChatMessageEntity(role = ChatRole.USER, text = text.take(CommandParser.MAX_LENGTH + 1), createdAt = now()))
        val zone = zone()
        val today = now().atZone(zone).toLocalDate()
        try {
            respond(CommandParser.parse(text, today), zone, today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e("Falha ao processar mensagem do chat", e)
            reply("Algo deu errado ao processar sua mensagem. Nada foi registrado.")
        }
    }

    private suspend fun respond(command: ParsedCommand, zone: ZoneId, today: LocalDate) {
        when (command) {
            is ParsedCommand.CreateTransaction -> proposeCreate(command, zone, today)
            ParsedCommand.Balance -> reply(ChatTexts.balance(summaries.balanceCents()))
            ParsedCommand.Today -> reply(ChatTexts.today(summaries.daily(today, zone), today))
            ParsedCommand.Month -> reply(ChatTexts.month(summaries.monthly(YearMonth.from(today), zone), today))
            is ParsedCommand.ListTransactions -> listTransactions(command.page, zone)
            ParsedCommand.Bills -> reply(ChatTexts.bills(billDao.listPendingDue(LIST_LIMIT)))
            ParsedCommand.Reminders -> reply(ChatTexts.reminders(reminderDao.listEnabled().take(LIST_LIMIT), zone))
            ParsedCommand.Undo -> proposeUndo(zone)
            ParsedCommand.Help -> reply(ChatTexts.HELP)
            is ParsedCommand.Clarify -> reply(command.question)
            ParsedCommand.Unknown -> reply(ChatTexts.UNKNOWN)
        }
    }

    private suspend fun proposeCreate(c: ParsedCommand.CreateTransaction, zone: ZoneId, today: LocalDate) {
        val category = categories.findByName(c.categoryName, c.kind)?.takeIf { !it.archived }
        val payload = ActionPayload.CreateTransaction(
            kind = c.kind,
            amountCents = c.amountCents,
            description = c.description,
            categoryId = category?.id,
            categoryName = category?.name,
            date = c.date,
        )
        val errors = validationErrors(payload, zone, today)
        if (errors != null) {
            reply(errors)
            return
        }
        propose(payload, ChatTexts.createPrompt(payload, today))
    }

    private suspend fun proposeUndo(zone: ZoneId) {
        val last = transactionDao.lastActiveBySource(TransactionSource.CHAT)
        if (last == null) {
            reply(ChatTexts.NOTHING_TO_UNDO)
            return
        }
        val payload = ActionPayload.DeleteTransaction(
            transactionId = last.id,
            kind = last.kind,
            amountCents = last.amountCents,
            description = last.description,
            date = last.occurredAt.atZone(zone).toLocalDate(),
        )
        propose(payload, ChatTexts.deletePrompt(payload))
    }

    private suspend fun listTransactions(page: Int, zone: ZoneId) {
        val rows = transactionDao.listRecentConfirmed(PAGE_SIZE + 1, (page - 1) * PAGE_SIZE)
        val hasMore = rows.size > PAGE_SIZE
        reply(
            ChatTexts.transactionList(rows.take(PAGE_SIZE), page, zone),
            quickReply = if (hasMore) "/gastos ${page + 1}" else null,
            quickReplyLabel = if (hasMore) "Ver mais" else null,
        )
    }

    /** Confirma o cartão como proposto. */
    suspend fun confirm(actionId: Long) {
        val zone = zone()
        val outcome = runWrite {
            pending.confirm(actionId) { action ->
                when (val payload = ActionPayload.decode(action.payloadJson)) {
                    is ActionPayload.CreateTransaction -> {
                        transactions.create(draftOf(payload, zone), TransactionSource.CHAT)
                        ChatTexts.created(payload, summaries.balanceCents())
                    }
                    is ActionPayload.DeleteTransaction -> {
                        transactions.softDelete(payload.transactionId)
                        ChatTexts.deleted(payload, summaries.balanceCents())
                    }
                    null -> error("Ação ilegível")
                }
            }
        }
        reply(outcome)
    }

    /** Confirma com valores corrigidos pelo usuário no diálogo de edição. O cartão continua de uso único. */
    suspend fun confirmEdited(actionId: Long, edited: ActionPayload.CreateTransaction) {
        val zone = zone()
        val today = now().atZone(zone).toLocalDate()
        validationErrors(edited, zone, today)?.let {
            reply(it)
            return
        }
        val categoryName = edited.categoryId?.let { id -> categories.listActive(edited.kind).firstOrNull { it.id == id }?.name }
        val final = edited.copy(categoryName = categoryName)
        val outcome = runWrite {
            pending.confirm(actionId) { action ->
                check(ActionPayload.decode(action.payloadJson) is ActionPayload.CreateTransaction) { "Cartão não editável" }
                transactions.create(draftOf(final, zone), TransactionSource.CHAT)
                ChatTexts.created(final, summaries.balanceCents())
            }
        }
        reply(outcome)
    }

    suspend fun cancel(actionId: Long) {
        reply(if (pending.cancel(actionId)) ChatTexts.CANCELLED else ChatTexts.ALREADY_RESOLVED)
    }

    suspend fun clearHistory() {
        chat.deleteAll()
        SafeLog.i("Histórico do chat apagado")
    }

    /** Executa a escrita confirmada; null (já usado/expirado) e erros viram mensagens, sem gravar nada. */
    private suspend fun runWrite(block: suspend () -> String?): String =
        try {
            block() ?: ChatTexts.ALREADY_RESOLVED
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidTransactionException) {
            "Não foi possível registrar: ${e.errors.joinToString(" ")}"
        } catch (e: Exception) {
            SafeLog.e("Falha ao confirmar ação do chat", e)
            "Não foi possível concluir. Nada foi alterado."
        }

    private fun validationErrors(p: ActionPayload.CreateTransaction, zone: ZoneId, today: LocalDate): String? {
        if (p.date.isAfter(today)) return "A data não pode estar no futuro. Contas a pagar chegam em breve."
        val result = TransactionRules.validate(draftOf(p, zone), now())
        return (result as? ValidationResult.Invalid)?.errors?.joinToString(" ")
    }

    /** Lançamento de hoje usa o instante atual; datas passadas usam meio-dia local, longe das viradas de dia. */
    private fun draftOf(p: ActionPayload.CreateTransaction, zone: ZoneId): TransactionDraft {
        val now = now()
        val occurredAt = if (p.date == now.atZone(zone).toLocalDate()) now else p.date.atTime(LocalTime.NOON).atZone(zone).toInstant()
        return TransactionDraft(p.kind, p.amountCents, p.description, p.categoryId, occurredAt = occurredAt)
    }

    private suspend fun propose(payload: ActionPayload, prompt: String) {
        val actionId = pending.create(ActionPayload.encode(payload), ActionOrigin.CHAT)
        chat.insert(ChatMessageEntity(role = ChatRole.ASSISTANT, text = prompt, createdAt = now(), relatedActionId = actionId))
    }

    private suspend fun reply(text: String, quickReply: String? = null, quickReplyLabel: String? = null) {
        chat.insert(
            ChatMessageEntity(
                role = ChatRole.ASSISTANT,
                text = text,
                createdAt = now(),
                quickReply = quickReply,
                quickReplyLabel = quickReplyLabel,
            ),
        )
    }

    private fun toItem(row: ChatRow): ChatItem {
        val m = row.message
        val card = m.relatedActionId?.let { id ->
            val status = when (row.actionStatus) {
                PendingActionStatus.CONFIRMED -> CardStatus.CONFIRMED
                PendingActionStatus.CANCELLED -> CardStatus.CANCELLED
                PendingActionStatus.PENDING, null ->
                    if ((row.actionExpiresAt ?: Instant.MIN) <= now()) CardStatus.EXPIRED else CardStatus.PENDING
            }
            ActionCard(id, row.actionPayload?.let(ActionPayload::decode), status)
        }
        return ChatItem(m.id, m.role, m.text, m.createdAt, card, m.quickReply, m.quickReplyLabel)
    }

    private suspend fun zone(): ZoneId =
        try {
            ZoneId.of(config.config.first().timezone)
        } catch (_: DateTimeException) {
            ZoneId.of("America/Sao_Paulo")
        }

    private fun now(): Instant = Instant.now(clock)

    /** Categorias disponíveis para o diálogo de edição. */
    suspend fun categoriesFor(kind: TransactionKind) = categories.listActive(kind)

    /** Data de hoje no fuso do usuário. */
    suspend fun today(): LocalDate = now().atZone(zone()).toLocalDate()

    companion object {
        const val HISTORY_LIMIT = 200
        const val PAGE_SIZE = 10
        const val LIST_LIMIT = 10
    }
}
