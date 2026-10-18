package br.com.finbot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    @Test
    fun `formatos brasileiros`() {
        assertEquals(123456L, Money.parseCents("R$ 1.234,56"))
        assertEquals(123456L, Money.parseCents("1.234,56"))
        assertEquals(123456L, Money.parseCents("1234,56"))
        assertEquals(4590L, Money.parseCents("45,9"))
        assertEquals(4590L, Money.parseCents("45,90"))
        assertEquals(4500L, Money.parseCents("45"))
        assertEquals(4500L, Money.parseCents("45 reais"))
        assertEquals(4500L, Money.parseCents("r$45"))
        assertEquals(123456789L, Money.parseCents("1.234.567,89"))
        assertEquals(5L, Money.parseCents("0,05"))
    }

    @Test
    fun `ponto decimal sem ambiguidade e ponto de milhar`() {
        assertEquals(123456L, Money.parseCents("1234.56"))
        assertEquals(4590L, Money.parseCents("45.9"))
        assertEquals(123400L, Money.parseCents("1.234"))
        assertEquals(123456700L, Money.parseCents("1.234.567"))
    }

    @Test
    fun `negativos para importacao`() {
        assertEquals(-123456L, Money.parseCents("R$ -1.234,56"))
        assertEquals(-4500L, Money.parseCents("-45"))
    }

    @Test
    fun `rejeita entradas invalidas ou ambiguas`() {
        listOf(
            "", "abc", "12,345", "1,2,3", "1.23.4", "45,", ",50", ".50", "1,234.56", "12.3456",
            "1.2345,00", "R$", "--5", "5-",
        ).forEach { assertNull(it, Money.parseCents(it)) }
    }

    @Test
    fun `nao perde centavos com valores grandes`() {
        assertEquals(99_999_999_999L, Money.parseCents("999.999.999,99"))
        // Soma em centavos é exata, ao contrário de 0.1 + 0.2 em Double.
        val total = listOf("0,10", "0,20").sumOf { Money.parseCents(it)!! }
        assertEquals(30L, total)
    }

    @Test
    fun `formatacao`() {
        assertEquals("R$ 0,00", Money.format(0))
        assertEquals("R$ 0,05", Money.format(5))
        assertEquals("R$ 45,90", Money.format(4590))
        assertEquals("R$ 1.234,56", Money.format(123456))
        assertEquals("R$ 1.000.000,00", Money.format(100_000_000))
        assertEquals("-R$ 1.234,56", Money.format(-123456))
    }

    @Test
    fun `ida e volta entre texto e centavos`() {
        listOf(1L, 99L, 100L, 4590L, 123456L, 100_000_000L, Money.MAX_CENTS).forEach { cents ->
            assertEquals(cents, Money.parseCents(Money.format(cents)))
        }
    }

    @Test
    fun `limites de valor de lancamento`() {
        assertFalse(Money.isValidAmount(0))
        assertFalse(Money.isValidAmount(-1))
        assertTrue(Money.isValidAmount(1))
        assertTrue(Money.isValidAmount(Money.MAX_CENTS))
        assertFalse(Money.isValidAmount(Money.MAX_CENTS + 1))
    }
}
