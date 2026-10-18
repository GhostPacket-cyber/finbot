package br.com.finbot.domain

import br.com.finbot.data.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Campos digitados no formulário de lançamento (texto cru). */
data class TransactionFormInput(
    val kind: TransactionKind = TransactionKind.EXPENSE,
    val amount: String = "",
    val description: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val date: String = "",
    val note: String = "",
)

data class TransactionFormErrors(
    val amount: String? = null,
    val description: String? = null,
    val date: String? = null,
    val note: String? = null,
    val general: List<String> = emptyList(),
) {
    val hasErrors get() = listOfNotNull(amount, description, date, note).isNotEmpty() || general.isNotEmpty()
}

sealed interface FormResult {
    data class Valid(val draft: TransactionDraft) : FormResult
    data class Invalid(val errors: TransactionFormErrors) : FormResult
}

/** Converte o formulário em [TransactionDraft], validado com as mesmas regras do repositório. Puro e testável. */
object TransactionForm {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

    fun formatDate(date: LocalDate): String = date.format(DATE)

    fun formatAmount(cents: Long): String = Money.format(cents).removePrefix("R$ ")

    fun parse(
        input: TransactionFormInput,
        zone: ZoneId,
        now: Instant,
        /** Instante original ao editar: mantido se a data não mudar (preserva a hora). */
        originalOccurredAt: Instant? = null,
    ): FormResult {
        val cents = Money.parseCents(input.amount)
        val amountError = when {
            input.amount.isBlank() -> "Informe o valor."
            cents == null -> "Valor inválido. Use, por exemplo, 45,90."
            !Money.isValidAmount(cents) -> "O valor deve ser maior que zero e até ${Money.format(Money.MAX_CENTS)}."
            else -> null
        }
        val description = input.description.trim()
        val descriptionError = when {
            description.isEmpty() -> "Informe uma descrição."
            description.length > TransactionRules.MAX_DESCRIPTION -> "Máximo de ${TransactionRules.MAX_DESCRIPTION} caracteres."
            else -> null
        }
        val date = try {
            LocalDate.parse(input.date.trim(), DATE)
        } catch (_: DateTimeParseException) {
            null
        }
        val today = now.atZone(zone).toLocalDate()
        val dateError = when {
            date == null -> "Data inválida (use dd/mm/aaaa)."
            date.isAfter(today) -> "A data não pode estar no futuro."
            else -> null
        }
        val note = input.note.trim()
        val noteError = if (note.length > TransactionRules.MAX_NOTE) "Máximo de ${TransactionRules.MAX_NOTE} caracteres." else null

        if (listOfNotNull(amountError, descriptionError, dateError, noteError).isNotEmpty()) {
            return FormResult.Invalid(TransactionFormErrors(amountError, descriptionError, dateError, noteError))
        }

        val occurredAt = when {
            originalOccurredAt != null && originalOccurredAt.atZone(zone).toLocalDate() == date -> originalOccurredAt
            date == today -> now
            else -> date!!.atTime(LocalTime.NOON).atZone(zone).toInstant()
        }
        val draft = TransactionDraft(
            kind = input.kind,
            amountCents = cents!!,
            description = description,
            categoryId = input.categoryId,
            accountId = input.accountId,
            occurredAt = occurredAt,
            note = note.ifEmpty { null },
        )
        return when (val v = TransactionRules.validate(draft, now)) {
            ValidationResult.Valid -> FormResult.Valid(draft)
            is ValidationResult.Invalid -> FormResult.Invalid(TransactionFormErrors(general = v.errors))
        }
    }
}
