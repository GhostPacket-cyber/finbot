package br.com.finbot.domain

import br.com.finbot.data.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TransactionFilterAndFormTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 10, 18)
    private val now = Instant.parse("2026-10-18T15:00:00Z") // 12:00 local

    @Test
    fun `periodos rapidos`() {
        assertEquals(Instant.parse("2026-10-01T03:00:00Z"), PeriodPreset.THIS_MONTH.range(today, zone)!!.start)
        assertEquals(Instant.parse("2026-09-01T03:00:00Z"), PeriodPreset.LAST_MONTH.range(today, zone)!!.start)
        assertEquals(Instant.parse("2026-10-01T03:00:00Z"), PeriodPreset.LAST_MONTH.range(today, zone)!!.end)
        val last30 = PeriodPreset.LAST_30_DAYS.range(today, zone)!!
        assertEquals(Instant.parse("2026-09-19T03:00:00Z"), last30.start)
        assertEquals(Instant.parse("2026-10-19T03:00:00Z"), last30.end)
        assertEquals(Instant.parse("2026-01-01T03:00:00Z"), PeriodPreset.THIS_YEAR.range(today, zone)!!.start)
        assertEquals(Instant.parse("2027-01-01T03:00:00Z"), PeriodPreset.THIS_YEAR.range(today, zone)!!.end)
        assertNull(PeriodPreset.ALL.range(today, zone))
        // Janeiro: "mês passado" é dezembro do ano anterior.
        assertEquals(Instant.parse("2025-12-01T03:00:00Z"), PeriodPreset.LAST_MONTH.range(LocalDate.of(2026, 1, 10), zone)!!.start)
    }

    @Test
    fun `busca trata curingas como texto`() {
        assertNull(SearchPattern.like("   "))
        assertEquals("%mercado%", SearchPattern.like(" mercado "))
        assertEquals("%50\\%%", SearchPattern.like("50%"))
        assertEquals("%a\\_b%", SearchPattern.like("a_b"))
        assertEquals("%c:\\\\x%", SearchPattern.like("c:\\x"))
        assertEquals(SearchPattern.MAX_LENGTH + 2, SearchPattern.like("x".repeat(500))!!.length)
    }

    @Test
    fun `filtro padrao e conversao para consulta`() {
        assertTrue(TransactionFilter().isDefault)
        val q = TransactionFilter(period = PeriodPreset.ALL, kind = TransactionKind.INCOME, search = "pix").toQuery(today, zone)
        assertNull(q.start)
        assertEquals(TransactionKind.INCOME, q.kind)
        assertEquals("%pix%", q.likePattern)
    }

    private fun input(amount: String = "45,90", description: String = "Mercado", date: String = "18/10/2026", note: String = "") =
        TransactionFormInput(TransactionKind.EXPENSE, amount, description, categoryId = 3, accountId = 1, date = date, note = note)

    @Test
    fun `formulario valido usa agora para hoje e meio dia para datas passadas`() {
        val todayDraft = (TransactionForm.parse(input(), zone, now) as FormResult.Valid).draft
        assertEquals(4_590L, todayDraft.amountCents)
        assertEquals(now, todayDraft.occurredAt)
        assertEquals(3L, todayDraft.categoryId)
        val past = (TransactionForm.parse(input(date = "15/10/2026"), zone, now) as FormResult.Valid).draft
        assertEquals(Instant.parse("2026-10-15T15:00:00Z"), past.occurredAt)
    }

    @Test
    fun `edicao preserva a hora original se a data nao mudou`() {
        val original = Instant.parse("2026-10-15T22:10:00Z") // 19:10 local de 15/10
        val draft = (TransactionForm.parse(input(date = "15/10/2026"), zone, now, original) as FormResult.Valid).draft
        assertEquals(original, draft.occurredAt)
        val moved = (TransactionForm.parse(input(date = "14/10/2026"), zone, now, original) as FormResult.Valid).draft
        assertEquals(Instant.parse("2026-10-14T15:00:00Z"), moved.occurredAt)
    }

    @Test
    fun `erros de campo`() {
        val errors = (TransactionForm.parse(input(amount = "", description = " ", date = "31/02/2026"), zone, now) as FormResult.Invalid).errors
        assertNotNull(errors.amount)
        assertNotNull(errors.description)
        assertNotNull(errors.date)
        listOf("0", "-5", "12,345", "abc").forEach {
            assertNotNull(it, (TransactionForm.parse(input(amount = it), zone, now) as FormResult.Invalid).errors.amount)
        }
        assertNotNull((TransactionForm.parse(input(date = "19/10/2026"), zone, now) as FormResult.Invalid).errors.date)
        assertNotNull((TransactionForm.parse(input(note = "x".repeat(501)), zone, now) as FormResult.Invalid).errors.note)
        assertTrue((TransactionForm.parse(input(date = "01/01/1999"), zone, now) as FormResult.Invalid).errors.general.isNotEmpty())
    }

    @Test
    fun `observacao vazia vira null e valor formatado para edicao`() {
        assertNull((TransactionForm.parse(input(note = "   "), zone, now) as FormResult.Valid).draft.note)
        assertEquals("1.234,56", TransactionForm.formatAmount(123_456))
        assertEquals("05/10/2026", TransactionForm.formatDate(LocalDate.of(2026, 10, 5)))
    }
}
