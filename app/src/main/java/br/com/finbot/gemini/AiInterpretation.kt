package br.com.finbot.gemini

import br.com.finbot.data.TransactionKind
import java.time.LocalDate

/** Intents que o modelo pode devolver. Qualquer outro valor invalida a resposta. */
enum class AiIntent {
    CREATE_TRANSACTION, QUERY_TRANSACTIONS, CREATE_BILL, MARK_BILL_PAID, CREATE_REMINDER, GET_REPORT, UNKNOWN
}

/** Consultas que o app sabe responder com números do SQLite. */
enum class AiReport { BALANCE, TODAY, MONTH, RECENT }

data class AiItem(
    val kind: TransactionKind,
    val amountCents: Long,
    val description: String,
    /** Nome canônico de uma categoria existente, ou null. */
    val category: String?,
    val date: LocalDate,
)

/** Resultado já validado. Nada aqui é gravado sem o cartão de confirmação. */
sealed interface AiInterpretation {
    val transcript: String?

    data class Transactions(val items: List<AiItem>, override val transcript: String?) : AiInterpretation
    data class Report(val report: AiReport, override val transcript: String?) : AiInterpretation
    data class Clarification(val question: String, override val transcript: String?) : AiInterpretation
    data class NotSupportedYet(val intent: AiIntent, override val transcript: String?) : AiInterpretation
    data class Unknown(override val transcript: String?) : AiInterpretation
}
