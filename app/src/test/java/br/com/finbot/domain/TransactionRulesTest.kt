package br.com.finbot.domain

import br.com.finbot.data.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TransactionRulesTest {

    private val now = Instant.parse("2026-10-17T15:00:00Z")

    private fun draft(
        amount: Long = 4500,
        description: String = "Almoço",
        at: Instant = now,
        note: String? = null,
    ) = TransactionDraft(TransactionKind.EXPENSE, amount, description, categoryId = null, occurredAt = at, note = note)

    @Test
    fun `lancamento valido`() {
        assertEquals(ValidationResult.Valid, TransactionRules.validate(draft(), now))
    }

    @Test
    fun `valor zero negativo ou acima do limite`() {
        listOf(0L, -1L, Money.MAX_CENTS + 1).forEach {
            assertTrue(TransactionRules.validate(draft(amount = it), now) is ValidationResult.Invalid)
        }
    }

    @Test
    fun `descricao obrigatoria e limitada`() {
        assertTrue(TransactionRules.validate(draft(description = "   "), now) is ValidationResult.Invalid)
        assertTrue(TransactionRules.validate(draft(description = "a".repeat(121)), now) is ValidationResult.Invalid)
        assertTrue(TransactionRules.validate(draft(description = "linha\u0007"), now) is ValidationResult.Invalid)
        assertEquals(ValidationResult.Valid, TransactionRules.validate(draft(description = "a".repeat(120)), now))
    }

    @Test
    fun `datas fora da faixa`() {
        assertTrue(TransactionRules.validate(draft(at = Instant.parse("1999-12-31T23:59:59Z")), now) is ValidationResult.Invalid)
        assertTrue(TransactionRules.validate(draft(at = now.plus(Duration.ofDays(2))), now) is ValidationResult.Invalid)
        assertEquals(ValidationResult.Valid, TransactionRules.validate(draft(at = now.plus(Duration.ofHours(12))), now))
    }

    @Test
    fun `observacao limitada`() {
        assertTrue(TransactionRules.validate(draft(note = "x".repeat(501)), now) is ValidationResult.Invalid)
        assertEquals(ValidationResult.Valid, TransactionRules.validate(draft(note = "x".repeat(500)), now))
    }

    @Test(expected = InvalidTransactionException::class)
    fun `requireValid lanca excecao com os erros`() {
        TransactionRules.requireValid(draft(amount = 0, description = ""), now)
    }
}
