package br.com.finbot.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

class AlertRulesTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val oct = YearMonth.of(2026, 10)
    private val now = at(2026, 10, 21, 12, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Instant = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    private fun item(uuid: String, cents: Long, desc: String, t: Instant, confirmed: Boolean = true, cat: Long? = 1) =
        SpendItem(uuid, cents, desc, cat, t, confirmed)

    // ---------- Orçamento ----------

    @Test
    fun `orcamento avisa em 80 e 100 por cento, limites exatos`() {
        val names = mapOf(1L to "Mercado")
        val limit = mapOf(1L to 50_000L)
        assertTrue(AlertRules.budget(oct, mapOf(1L to 39_999L), limit, names).isEmpty()) // 79,99%
        val at80 = AlertRules.budget(oct, mapOf(1L to 40_000L), limit, names).single() // exatamente 80%
        assertEquals("alert:budget:1:2026-10:80", at80.key)
        assertTrue(at80.text.contains("80%") && at80.text.contains("Restam R$ 100,00"))
        val at99 = AlertRules.budget(oct, mapOf(1L to 49_999L), limit, names).single()
        assertEquals("alert:budget:1:2026-10:80", at99.key)
        val at100 = AlertRules.budget(oct, mapOf(1L to 50_000L), limit, names).single() // exatamente 100%
        assertEquals("alert:budget:1:2026-10:100", at100.key)
        assertEquals(listOf("alert:budget:1:2026-10:80"), at100.alsoMarks)
        val over = AlertRules.budget(oct, mapOf(1L to 52_000L), limit, names).single()
        assertTrue(over.text.contains("104%"))
        // Sem gasto, limite zero (removido) e categoria sem orçamento não avisam.
        assertTrue(AlertRules.budget(oct, emptyMap(), limit, names).isEmpty())
        assertTrue(AlertRules.budget(oct, mapOf(1L to 9_999L), mapOf(1L to 0L), names).isEmpty())
        assertTrue(AlertRules.budget(oct, mapOf(2L to 99_999L), limit, names).isEmpty())
        // A chave muda com o mês: novembro avisa de novo.
        assertEquals("alert:budget:1:2026-11:80", AlertRules.budget(oct.plusMonths(1), mapOf(1L to 40_000L), limit, names).single().key)
    }

    // ---------- Cobrança duplicada ----------

    @Test
    fun `duplicada dentro da janela, fora da janela e nos limites`() {
        val a = item("a", 4_590, "PADARIA SAO JOSE", at(2026, 10, 20, 9))
        val b = item("b", 4_590, "Padaria São José LTDA", at(2026, 10, 21, 9)) // 24 h exatas
        val alerts = AlertRules.duplicateCharges(listOf(b, a), 24, now, zone)
        assertEquals(listOf("alert:dup:a:b"), alerts.map { it.key })
        assertTrue(alerts.single().text.contains("R$ 45,90"))
        // 24 h + 1 min: fora.
        val c = item("c", 4_590, "PADARIA SAO JOSE", at(2026, 10, 21, 9, 1))
        assertTrue(AlertRules.duplicateCharges(listOf(a, c), 24, now, zone).isEmpty())
        // Janela configurável.
        assertEquals(1, AlertRules.duplicateCharges(listOf(a, c), 48, now, zone).size)
        // Valor diferente ou estabelecimento diferente: nada.
        assertTrue(AlertRules.duplicateCharges(listOf(a, item("d", 4_591, "PADARIA SAO JOSE", at(2026, 10, 20, 10))), 24, now, zone).isEmpty())
        assertTrue(AlertRules.duplicateCharges(listOf(a, item("e", 4_590, "FARMACIA CENTRAL", at(2026, 10, 20, 10))), 24, now, zone).isEmpty())
        // Antigo (mais de 7 dias): nada, para não alertar sobre o passado.
        val old1 = item("f", 1_000, "UBER TRIP", at(2026, 10, 1, 9))
        val old2 = item("g", 1_000, "UBER *TRIP", at(2026, 10, 1, 10))
        assertTrue(AlertRules.duplicateCharges(listOf(old1, old2), 24, now, zone).isEmpty())
    }

    @Test
    fun `semelhanca de estabelecimento`() {
        assertTrue(AlertRules.similar("UBER *TRIP 1234", "Uber Trip"))
        assertTrue(AlertRules.similar("PAG*NETFLIX.COM", "Netflix"))
        assertTrue(AlertRules.similar("Mercado Bom Preço", "MERCADO BOM PRECO FILIAL 2"))
        assertFalse(AlertRules.similar("Mercado Bom Preço", "Farmácia Bom Preço"))
        assertFalse(AlertRules.similar("Spotify", "Netflix"))
    }

    // ---------- Assinatura ----------

    @Test
    fun `assinatura em meses consecutivos, inclusive na virada do ano`() {
        val sep = item("s1", 3_990, "NETFLIX.COM", at(2026, 9, 18))
        val oct1 = item("s2", 3_990, "PAG*NETFLIX.COM", at(2026, 10, 18))
        val alert = AlertRules.subscriptions(listOf(sep, oct1), emptyList(), now, zone).single()
        assertTrue(alert.key.startsWith("alert:sub:"))
        assertTrue(alert.text.contains("setembro/2026") && alert.text.contains("outubro/2026") && alert.text.contains("R$ 39,90"))
        // A chave é estável (mesmo estabelecimento e valor nos meses seguintes não repetem).
        val nov = item("s3", 3_990, "NETFLIX.COM", at(2026, 11, 18))
        assertEquals(alert.key, AlertRules.subscriptions(listOf(oct1, nov), emptyList(), at(2026, 11, 19), zone).single().key)
        // Virada do ano.
        val dec = item("y1", 2_190, "Spotify", at(2026, 12, 10))
        val jan = item("y2", 2_190, "Spotify", at(2027, 1, 10))
        assertEquals(1, AlertRules.subscriptions(listOf(dec, jan), emptyList(), at(2027, 1, 11), zone).size)
    }

    @Test
    fun `nao e assinatura`() {
        val sep = item("s1", 3_990, "NETFLIX.COM", at(2026, 9, 18))
        // Pulo de mês.
        val aug = item("s0", 3_990, "NETFLIX.COM", at(2026, 8, 18))
        assertTrue(AlertRules.subscriptions(listOf(aug, item("x", 3_990, "NETFLIX.COM", at(2026, 10, 18))), emptyList(), now, zone).isEmpty())
        // Valor diferente.
        assertTrue(AlertRules.subscriptions(listOf(sep, item("x", 4_490, "NETFLIX.COM", at(2026, 10, 18))), emptyList(), now, zone).isEmpty())
        // Duas vezes no mesmo mês.
        assertTrue(AlertRules.subscriptions(listOf(item("x", 3_990, "NETFLIX.COM", at(2026, 10, 2)), item("y", 3_990, "NETFLIX.COM", at(2026, 10, 18))), emptyList(), now, zone).isEmpty())
        // Já cadastrada como conta.
        val oct1 = item("s2", 3_990, "NETFLIX.COM", at(2026, 10, 18))
        assertTrue(AlertRules.subscriptions(listOf(sep, oct1), listOf(AlertRules.KnownBill("Netflix", 3_990)), now, zone).isEmpty())
        // Sugestão ainda não confirmada não conta.
        assertTrue(AlertRules.subscriptions(listOf(sep, oct1.copy(confirmed = false)), emptyList(), now, zone).isEmpty())
        // Última ocorrência antiga.
        assertTrue(AlertRules.subscriptions(listOf(sep, oct1), emptyList(), at(2026, 11, 2), zone).isEmpty())
    }

    // ---------- Gasto fora do padrão ----------

    @Test
    fun `fora do padrao com limiar configuravel e bordas`() {
        val names = mapOf(1L to "Lazer")
        fun months(cur: Long, m1: Long, m2: Long, m3: Long) =
            mapOf(1L to mapOf(oct to cur, oct.minusMonths(1) to m1, oct.minusMonths(2) to m2, oct.minusMonths(3) to m3))
        // Média 30.000; +50% = 45.000. Exatamente 45.000: não avisa (precisa passar).
        assertTrue(AlertRules.unusualSpending(oct, months(45_000, 30_000, 30_000, 30_000), names, 50).isEmpty())
        val a = AlertRules.unusualSpending(oct, months(45_001, 30_000, 30_000, 30_000), names, 50).single()
        assertEquals("alert:unusual:1:2026-10", a.key)
        assertTrue(a.text.contains("50% acima") && a.text.contains("R$ 300,00"))
        // Limiar de 100%.
        assertTrue(AlertRules.unusualSpending(oct, months(45_001, 30_000, 30_000, 30_000), names, 100).isEmpty())
        // Média com mês zerado (inclui o zero) mas exige 2 meses com gasto.
        assertEquals(1, AlertRules.unusualSpending(oct, months(40_000, 30_000, 30_000, 0), names, 50).size)
        assertTrue(AlertRules.unusualSpending(oct, months(90_000, 30_000, 0, 0), names, 50).isEmpty())
        // Diferença pequena demais em valor absoluto.
        assertTrue(AlertRules.unusualSpending(oct, months(1_500, 600, 600, 600), names, 50).isEmpty())
    }

    // ---------- Resumo semanal ----------

    @Test
    fun `horario do resumo semanal e recuperacao`() {
        val mon9 = LocalTime.of(9, 0)
        // Quarta 21/10 12:00 → último slot segunda 19/10 09:00: passou há 2 dias e 3 h → pula.
        assertNull(AlertRules.weeklyKey(now, zone, DayOfWeek.MONDAY, mon9))
        // Segunda 19/10 09:00 em ponto → entrega.
        assertEquals("alert:weekly:2026-10-19", AlertRules.weeklyKey(at(2026, 10, 19, 9, 0), zone, DayOfWeek.MONDAY, mon9))
        // Segunda 08:59 → o slot é o da semana anterior (12/10), que já venceu há mais de 2 dias.
        assertNull(AlertRules.weeklyKey(at(2026, 10, 19, 8, 59), zone, DayOfWeek.MONDAY, mon9))
        // Quarta 21/10 às 08:00 com resumo na quarta 09:00: slot de 14/10, atrasado → pula.
        assertNull(AlertRules.weeklyKey(at(2026, 10, 21, 8, 0), zone, DayOfWeek.WEDNESDAY, mon9))
        // Até 2 dias de atraso ainda entrega a mesma chave.
        assertEquals("alert:weekly:2026-10-19", AlertRules.weeklyKey(at(2026, 10, 21, 9, 0), zone, DayOfWeek.MONDAY, mon9))
        val text = AlertRules.weeklySummary(
            "k",
            AlertRules.WeeklyFacts(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18), 100_000, 45_990, "Mercado", 20_000, 2, 1, 15_000),
        ).text
        assertTrue(text.contains("12/10 a 18/10") && text.contains("despesas R$ 459,90") && text.contains("Mercado (R$ 200,00)"))
        assertTrue(text.contains("Contas nos próximos 7 dias: 1") && text.contains("2 sugestão"))
    }
}
