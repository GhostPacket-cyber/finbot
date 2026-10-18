package br.com.finbot.scheduling

import br.com.finbot.data.ShortMonthPolicy.FIRST_DAY_OF_NEXT_MONTH
import br.com.finbot.data.ShortMonthPolicy.LAST_DAY_OF_MONTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceAndPlannerTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `dia 31 em meses curtos com as duas regras e ancora fixa`() {
        val first = d("2026-01-31")
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31").map(::d),
            (0L..4L).map { Recurrence.monthlyDate(first, it, LAST_DAY_OF_MONTH) },
        )
        assertEquals(
            listOf("2026-01-31", "2026-03-01", "2026-03-31", "2026-05-01").map(::d),
            (0L..3L).map { Recurrence.monthlyDate(first, it, FIRST_DAY_OF_NEXT_MONTH) },
        )
    }

    @Test
    fun `fevereiro bissexto e dia 29`() {
        assertEquals(d("2028-02-29"), Recurrence.monthlyDate(d("2028-01-29"), 1, LAST_DAY_OF_MONTH))
        assertEquals(d("2027-02-28"), Recurrence.monthlyDate(d("2027-01-29"), 1, LAST_DAY_OF_MONTH))
        assertEquals(d("2027-03-01"), Recurrence.monthlyDate(d("2027-01-30"), 1, FIRST_DAY_OF_NEXT_MONTH))
    }

    @Test
    fun `vencimentos num intervalo`() {
        val dates = Recurrence.dueDates(d("2026-01-10"), Recurrence.MONTHLY, LAST_DAY_OF_MONTH, d("2026-09-15"), d("2026-11-20"))
        assertEquals(listOf("2026-10-10", "2026-11-10").map(::d), dates)
        // A regra "1º do mês seguinte" pode cair dentro do intervalo vinda do mês anterior.
        val shifted = Recurrence.dueDates(d("2026-01-31"), Recurrence.MONTHLY, FIRST_DAY_OF_NEXT_MONTH, d("2026-03-01"), d("2026-03-31"))
        assertEquals(listOf("2026-03-01", "2026-03-31").map(::d), shifted)
        assertEquals(emptyList<LocalDate>(), Recurrence.dueDates(d("2026-12-01"), Recurrence.MONTHLY, LAST_DAY_OF_MONTH, d("2026-10-01"), d("2026-11-30")))
        assertEquals(listOf(d("2026-10-20")), Recurrence.dueDates(d("2026-10-20"), null, LAST_DAY_OF_MONTH, d("2026-10-01"), d("2026-10-31")))
        assertEquals(emptyList<LocalDate>(), Recurrence.dueDates(d("2026-10-20"), null, LAST_DAY_OF_MONTH, d("2026-10-21"), d("2026-10-31")))
    }

    @Test
    fun `repeticao de lembretes pula perdidos e mantem a ancora`() {
        val t = LocalDateTime.parse("2026-10-17T20:00")
        assertNull(ReminderRepeat.Once.nextAfter(t, t))
        assertEquals(LocalDateTime.parse("2026-10-21T20:00"), ReminderRepeat.Daily.nextAfter(t, LocalDateTime.parse("2026-10-20T21:00")))
        assertEquals(LocalDateTime.parse("2026-10-24T20:00"), ReminderRepeat.Weekly.nextAfter(t, t))
        val m = ReminderRepeat.Monthly(31)
        assertEquals(LocalDateTime.parse("2026-02-28T09:00"), m.nextAfter(LocalDateTime.parse("2026-01-31T09:00"), LocalDateTime.parse("2026-01-31T09:00")))
        assertEquals(LocalDateTime.parse("2026-03-31T09:00"), m.nextAfter(LocalDateTime.parse("2026-02-28T09:00"), LocalDateTime.parse("2026-02-28T09:00")))
        listOf(ReminderRepeat.Once, ReminderRepeat.Daily, ReminderRepeat.Weekly, ReminderRepeat.Monthly(5)).forEach {
            assertEquals(it, ReminderRepeat.decode(it.encode()))
        }
    }

    @Test
    fun `offsets`() {
        assertEquals(listOf(3, 0, -1), ReminderOffsets.parse("0, 3,-1,3,abc,99"))
        assertEquals("3,0,-1", ReminderOffsets.encode(listOf(-1, 3, 0)))
        assertEquals("No dia", ReminderOffsets.label(0))
        assertEquals("3 dias depois", ReminderOffsets.label(-3))
    }

    private val luz = PendingBill("u1", "Luz", 18_990, d("2026-10-20"), listOf(3, 0, -1))

    @Test
    fun `aviso antes do vencimento so a partir das 9h do dia certo`() {
        val before = Instant.parse("2026-10-17T11:59:00Z") // 08:59 local de 17/10
        assertTrue(DueEventPlanner.planBills(listOf(luz), before, zone).due.isEmpty())
        val at = Instant.parse("2026-10-17T12:00:00Z") // 09:00 local
        val plan = DueEventPlanner.planBills(listOf(luz), at, zone)
        assertEquals(listOf("bill:u1:2026-10-20:3"), plan.due.map { it.key })
        assertEquals("R$ 189,90 vence em 3 dias (20/10).", plan.due.single().body)
    }

    @Test
    fun `atraso de entrega recebe contexto e eventos muito antigos sao pulados`() {
        val late = Instant.parse("2026-10-20T14:10:00Z") // 11:10 local de 20/10
        val plan = DueEventPlanner.planBills(listOf(luz), late, zone)
        // Dois avisos vencidos juntos: só o mais recente é entregue; o outro fica registrado como pulado.
        assertEquals(listOf("bill:u1:2026-10-20:0"), plan.due.map { it.key })
        assertEquals(listOf("bill:u1:2026-10-20:3"), plan.stale.map { it.key })
        assertEquals("R$ 189,90 vence hoje. (aviso com atraso de 2 h)", plan.due.single().body)

        // 27/10 10:00 local: os avisos de 17/10 e 20/10 passaram de 7 dias; o de 21/10 (1 dia depois) ainda não.
        val muchLater = Instant.parse("2026-10-27T13:00:00Z")
        val old = DueEventPlanner.planBills(listOf(luz), muchLater, zone)
        assertEquals(setOf("bill:u1:2026-10-20:3", "bill:u1:2026-10-20:0"), old.stale.map { it.key }.toSet())
        assertEquals(listOf("bill:u1:2026-10-20:-1"), old.due.map { it.key })
        assertTrue(old.due.single().body.startsWith("R$ 189,90 está atrasada há 7 dias"))
    }

    @Test
    fun `conta cadastrada depois do horario do aviso nao gera aviso atrasado`() {
        // Cadastrada em 20/10 às 14h, vencendo no mesmo dia: os avisos de 17/10 e 20/10 9h são anteriores ao cadastro.
        val created = Instant.parse("2026-10-20T17:00:00Z")
        val bill = luz.copy(createdAt = created)
        val plan = DueEventPlanner.planBills(listOf(bill), created.plusSeconds(60), zone)
        assertTrue(plan.due.isEmpty())
        assertEquals(setOf("bill:u1:2026-10-20:3", "bill:u1:2026-10-20:0"), plan.stale.map { it.key }.toSet())
        // O aviso de atraso do dia seguinte continua valendo.
        val next = DueEventPlanner.planBills(listOf(bill), Instant.parse("2026-10-21T12:30:00Z"), zone)
        assertEquals(listOf("bill:u1:2026-10-20:-1"), next.due.map { it.key })
    }

    @Test
    fun `chave nao depende do fuso`() {
        val now = Instant.parse("2026-10-21T15:00:00Z")
        val sp = DueEventPlanner.planBills(listOf(luz), now, zone).due.map { it.key }
        val manaus = DueEventPlanner.planBills(listOf(luz), now, ZoneId.of("America/Manaus")).due.map { it.key }
        assertEquals(sp, manaus)
    }

    @Test
    fun `lembrete pessoal`() {
        val at = Instant.parse("2026-10-17T23:00:00Z")
        val e = DueEventPlanner.reminderEvent("r1", "Ligar para o banco", at, at.plusSeconds(60))
        assertEquals("reminder:r1:${at.toEpochMilli()}", e.key)
        assertEquals("Ligar para o banco", e.body)
        assertEquals(" (aviso com atraso de 1 dia(s))", DueEventPlanner.lateNote(at, at.plusSeconds(90_000)))
    }

}
