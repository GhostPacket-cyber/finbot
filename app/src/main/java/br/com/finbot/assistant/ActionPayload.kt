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
        /** Interpretado pelo Gemini (grava com origem AI) ou pelo parser local (origem CHAT). */
        val viaAi: Boolean = false,
    ) : ActionPayload

    /** Vários lançamentos de uma mensagem (via IA): confirmados juntos, numa única transação. */
    data class CreateBatch(val items: List<CreateTransaction>) : ActionPayload {
        init {
            require(items.size >= 2) { "Lote precisa de 2 ou mais itens" }
        }

        /** Totais calculados aqui, em Kotlin, nunca pelo modelo. */
        val expenseCents: Long get() = items.filter { it.kind == TransactionKind.EXPENSE }.sumOf { it.amountCents }
        val incomeCents: Long get() = items.filter { it.kind == TransactionKind.INCOME }.sumOf { it.amountCents }
    }

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
        private const val BATCH = "CREATE_BATCH"

        private fun encodeCreate(p: CreateTransaction): JSONObject = JSONObject()
            .put(TYPE, CREATE)
            .put("kind", p.kind.name)
            .put("amount_cents", p.amountCents)
            .put("description", p.description)
            .put("category_id", p.categoryId ?: JSONObject.NULL)
            .put("category_name", p.categoryName ?: JSONObject.NULL)
            .put("date", p.date.toString())
            .put("via_ai", p.viaAi)

        private fun decodeCreate(o: JSONObject) = CreateTransaction(
            kind = TransactionKind.valueOf(o.getString("kind")),
            amountCents = o.getLong("amount_cents"),
            description = o.getString("description"),
            categoryId = if (o.isNull("category_id")) null else o.getLong("category_id"),
            categoryName = if (o.isNull("category_name")) null else o.getString("category_name"),
            date = LocalDate.parse(o.getString("date")),
            viaAi = o.optBoolean("via_ai", false),
        )

        fun encode(payload: ActionPayload): String = when (payload) {
            is CreateTransaction -> encodeCreate(payload).toString()
            is CreateBatch -> JSONObject()
                .put(TYPE, BATCH)
                .put("items", org.json.JSONArray().apply { payload.items.forEach { put(encodeCreate(it)) } })
                .toString()
            is DeleteTransaction -> encodeDelete(payload)
        }

        private fun encodeDelete(payload: DeleteTransaction): String = JSONObject()
            .put(TYPE, DELETE)
            .put("transaction_id", payload.transactionId)
            .put("kind", payload.kind.name)
            .put("amount_cents", payload.amountCents)
            .put("description", payload.description)
            .put("date", payload.date.toString())
            .toString()

        /** Retorna null para JSON malformado ou de tipo desconhecido (nunca executa algo que não entende). */
        fun decode(json: String): ActionPayload? = runCatching {
            val o = JSONObject(json)
            when (o.getString(TYPE)) {
                CREATE -> decodeCreate(o)
                BATCH -> {
                    val array = o.getJSONArray("items")
                    CreateBatch((0 until array.length()).map { decodeCreate(array.getJSONObject(it)) })
                }
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
