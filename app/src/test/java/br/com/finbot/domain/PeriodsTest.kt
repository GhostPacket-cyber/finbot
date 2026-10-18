package br.com.finbot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class PeriodsTest {

    private val saoPaulo = ZoneId.of("America/Sao_Paulo")
    private val manaus = ZoneId.of("America/Manaus")

    @Test
    fun `mes de outubro em Sao Paulo`() {
        val range = Periods.month(YearMonth.of(2026, 10), saoPaulo)
        assertEquals(Instant.parse("2026-10-01T03:00:00Z"), range.start)
        assertEquals(Instant.parse("2026-11-01T03:00:00Z"), range.end)
    }

    @Test
    fun `ultimo minuto do mes local pertence ao mes mesmo ja sendo outro mes em UTC`() {
        val october = Periods.month(YearMonth.of(2026, 10), saoPaulo)
        val lastMinuteLocal = Instant.parse("2026-11-01T02:59:00Z") // 31/10 23:59 em São Paulo
        val firstMinuteNovember = Instant.parse("2026-11-01T03:00:00Z") // 01/11 00:00 em São Paulo
        assertTrue(lastMinuteLocal in october)
        assertFalse(firstMinuteNovember in october)
        assertEquals(YearMonth.of(2026, 10), Periods.monthOf(lastMinuteLocal, saoPaulo))
        assertEquals(YearMonth.of(2026, 11), Periods.monthOf(firstMinuteNovember, saoPaulo))
    }

    @Test
    fun `fevereiro e anos bissextos`() {
        val feb2028 = Periods.month(YearMonth.of(2028, 2), saoPaulo)
        assertEquals(LocalDate.of(2028, 3, 1), Periods.dateOf(feb2028.end, saoPaulo))
        assertTrue(Instant.parse("2028-03-01T02:59:59Z") in feb2028) // 29/02 23:59:59 local
    }

    @Test
    fun `virada de ano`() {
        val december = Periods.month(YearMonth.of(2026, 12), saoPaulo)
        assertEquals(Instant.parse("2027-01-01T03:00:00Z"), december.end)
    }

    @Test
    fun `dia depende do fuso do usuario`() {
        val instant = Instant.parse("2026-10-18T03:30:00Z")
        assertEquals(LocalDate.of(2026, 10, 18), Periods.dateOf(instant, saoPaulo)) // 00:30
        assertEquals(LocalDate.of(2026, 10, 17), Periods.dateOf(instant, manaus)) // 23:30
        val day = Periods.day(LocalDate.of(2026, 10, 17), manaus)
        assertTrue(instant in day)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `intervalo invertido e rejeitado`() {
        InstantRange(Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"))
    }
}
