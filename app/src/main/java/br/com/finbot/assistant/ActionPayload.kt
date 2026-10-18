package br.com.finbot.assistant

import br.com.finbot.data.TransactionKind
import org.json.JSONObject
import java.time.LocalDate

/** Conteúdo de uma ação pendente (cartão de confirmação), serializado em `pending_actions.payload_json`. */
sealed interface ActionPayload {

    data class CreateTransaction(
        val kind: TransactionKind,
        val amountCents: Long,
        val description: String,
        val categoryId: Long?,
        val categoryName: String?,
        val date: LocalDate,
    ) : ActionPayload

    data class DeleteTransaction(
        val transactionId: Long,
        val kind: TransactionKind,
        val amountCents: Long,
        val description: String,
        val date: LocalDate,
    ) : ActionPayload

    companion object {
        private const val TYPE = "type"
        private const val CREATE = "CREATE_TRANSACTION"
        private const val DELETE = "DELETE_TRANSACTION"

        fun encode(payload: ActionPayload): String = JSONObject().apply {
            when (payload) {
                is CreateTransaction -> {
                    put(TYPE, CREATE)
                    put("kind", payload.kind.name)
                    put("amount_cents", payload.amountCents)
                    put("description", payload.description)
                    put("category_id", payload.categoryId ?: JSONObject.NULL)
                    put("category_name", payload.categoryName ?: JSONObject.NULL)
                    put("date", payload.date.toString())
                }
                is DeleteTransaction -> {
                    put(TYPE, DELETE)
                    put("transaction_id", payload.transactionId)
                    put("kind", payload.kind.name)
                    put("amount_cents", payload.amountCents)
                    put("description", payload.description)
                    put("date", payload.date.toString())
                }
            }
        }.toString()

        /** Retorna null para JSON malformado ou de tipo desconhecido (nunca executa algo que não entende). */
        fun decode(json: String): ActionPayload? = runCatching {
            val o = JSONObject(json)
            when (o.getString(TYPE)) {
                CREATE -> CreateTransaction(
                    kind = TransactionKind.valueOf(o.getString("kind")),
                    amountCents = o.getLong("amount_cents"),
                    description = o.getString("description"),
                    categoryId = if (o.isNull("category_id")) null else o.getLong("category_id"),
                    categoryName = if (o.isNull("category_name")) null else o.getString("category_name"),
                    date = LocalDate.parse(o.getString("date")),
                )
                DELETE -> DeleteTransaction(
                    transactionId = o.getLong("transaction_id"),
                    kind = TransactionKind.valueOf(o.getString("kind")),
                    amountCents = o.getLong("amount_cents"),
                    description = o.getString("description"),
                    date = LocalDate.parse(o.getString("date")),
                )
                else -> null
            }
        }.getOrNull()
    }
}
