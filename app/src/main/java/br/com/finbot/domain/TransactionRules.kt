package br.com.finbot.domain

import br.com.finbot.data.TransactionKind
import java.time.Duration
import java.time.Instant

/** Dados de entrada para criar ou editar um lançamento, antes da persistência. */
data class TransactionDraft(
    val kind: TransactionKind,
    val amountCents: Long,
    val description: String,
    val categoryId: Long?,
    val accountId: Long? = null,
    val occurredAt: Instant,
    val note: String? = null,
)

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val errors: List<String>) : ValidationResult
}

class InvalidTransactionException(val errors: List<String>) : IllegalArgumentException(errors.joinToString("; "))

object TransactionRules {
    const val MAX_DESCRIPTION = 120
    const val MAX_NOTE = 500

    /** Datas muito antigas costumam ser erro de digitação ou de interpretação. */
    val MIN_DATE: Instant = Instant.parse("2000-01-01T00:00:00Z")

    /** Tolerância para relógio e fuso; lançamentos futuros de fato são contas a pagar, não despesas. */
    val MAX_FUTURE: Duration = Duration.ofDays(1)

    fun validate(draft: TransactionDraft, now: Instant): ValidationResult {
        val errors = buildList {
            if (!Money.isValidAmount(draft.amountCents)) {
                add("O valor deve ser maior que zero e no máximo ${Money.format(Money.MAX_CENTS)}.")
            }
            val description = draft.description.trim()
            if (description.isEmpty()) add("Informe uma descrição.")
            if (description.length > MAX_DESCRIPTION) add("A descrição deve ter no máximo $MAX_DESCRIPTION caracteres.")
            if (description.any { it.isISOControl() }) add("A descrição contém caracteres inválidos.")
            if ((draft.note?.length ?: 0) > MAX_NOTE) add("A observação deve ter no máximo $MAX_NOTE caracteres.")
            if (draft.occurredAt < MIN_DATE) add("Data anterior a 2000 não é aceita.")
            if (draft.occurredAt > now.plus(MAX_FUTURE)) add("A data não pode estar no futuro. Use uma conta a pagar.")
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    fun requireValid(draft: TransactionDraft, now: Instant) {
        val result = validate(draft, now)
        if (result is ValidationResult.Invalid) throw InvalidTransactionException(result.errors)
    }
}
