package br.com.finbot.scheduling

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import br.com.finbot.data.BillOccurrenceStatus
import br.com.finbot.data.NotificationStatus
import br.com.finbot.data.ShortMonthPolicy
import br.com.finbot.data.TestFinance
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.repository.BillRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ReminderProcessorTest {

    private lateinit var f: TestFinance

    @Before
    fun setUp() = runTest {
        f = TestFinance(br.com.finbot.data.MutableClock(LocalDateTime.parse("2026-10-10T10:00").atZone(ZoneId.of("America/Sao_Paulo")).toInstant()))
        f.seed()
    }

    @After
    fun tearDown() = f.close()

    /** Instante local de São Paulo (UTC−3). */
    private fun sp(local: String): Instant = LocalDateTime.parse(local).atZone(ZoneId.of("America/Sao_Paulo")).toInstant()

    private suspend fun luz(offsets: List<Int> = listOf(3, 0, -1)) = f.bills.createBill(
        "Luz", 18_990, f.categoryId("Moradia", TransactionKind.EXPENSE), LocalDate.of(2026, 10, 20),
        BillRepository.MONTHLY, remindOffsets = offsets,
    )

    @Test
    fun avisoAntesDoVencimentoUmaUnicaVezMesmoAposReinicio() = runTest {
        luz()
        f.clock.current = sp("2026-10-17T08:59")
        assertEquals(0, f.processor.run().delivered)

        f.clock.current = sp("2026-10-17T09:05")
        assertEquals(1, f.processor.run().delivered)
        assertEquals("R$ 189,90 vence em 3 dias (20/10).", f.notifier.shown.single().body)

        assertEquals("segunda execução não repete", 0, f.processor.run().delivered)
        // "Reinício do app": nova instância do processador sobre o mesmo banco.
        assertEquals(0, f.newProcessor().run().delivered)
        assertEquals(1, f.notifier.shown.size)
    }

    @Test
    fun contaCadastradaHojeAposAs9hNaoNotificaNaHora() = runTest {
        // Cenário real visto no aparelho: cadastro às 14h de uma conta que vence no mesmo dia.
        f.clock.current = sp("2026-10-20T14:00")
        f.bills.createBill("Água", 7_000, null, LocalDate.of(2026, 10, 20), remindOffsets = listOf(3, 0, -1))
        assertEquals(0, f.processor.run().delivered)
        f.clock.current = sp("2026-10-21T09:15")
        assertEquals("o aviso de atraso do dia seguinte chega", 1, f.processor.run().delivered)
        assertTrue(f.notifier.shown.single().body.contains("venceu ontem"))
    }

    @Test
    fun mudancaDeFusoNaoDuplica() = runTest {
        luz()
        f.clock.current = sp("2026-10-20T09:30")
        f.processor.run()
        val before = f.notifier.shown.map { it.key }
        f.zone = ZoneId.of("America/Manaus") // usuário viajou
        f.processor.run()
        assertEquals(before, f.notifier.shown.map { it.key })
    }

    @Test
    fun contaPagaNaoGeraMaisAvisosNemDuplicaLancamento() = runTest {
        luz()
        f.clock.current = sp("2026-10-17T10:00")
        f.processor.run()
        val occ = f.db.billDao().findOccurrence(f.db.billDao().listActiveBills().single().id, LocalDate.of(2026, 10, 20))!!
        f.clock.current = sp("2026-10-18T13:00")
        val tx1 = f.bills.markPaid(occ.id, sp("2026-10-18T12:00"))
        val tx2 = f.bills.markPaid(occ.id, sp("2026-10-18T12:00"))
        assertEquals(tx1, tx2)
        assertEquals(-18_990L, f.summaries.balanceCents())

        f.clock.current = sp("2026-10-21T10:00") // dia do vencimento e do atraso: nada, pois está paga
        f.processor.run()
        assertEquals(listOf("bill:${f.db.billDao().listActiveBills().single().uuid}:2026-10-20:3"), f.notifier.shown.map { it.key })
    }

    @Test
    fun recorrenciaEmMesesCurtos() = runTest {
        f.clock.current = sp("2026-01-31T10:00")
        val last = f.bills.createBill("Aluguel", 150_000, null, LocalDate.of(2026, 1, 31), BillRepository.MONTHLY)
        val next = f.bills.createBill("Condomínio", 60_000, null, LocalDate.of(2026, 1, 31), BillRepository.MONTHLY, ShortMonthPolicy.FIRST_DAY_OF_NEXT_MONTH)
        f.clock.current = sp("2026-02-25T10:00")
        f.bills.ensureOccurrencesAround(LocalDate.of(2026, 2, 25))
        f.bills.ensureOccurrencesAround(LocalDate.of(2026, 2, 25)) // idempotente
        assertNotNull(f.db.billDao().findOccurrence(last, LocalDate.of(2026, 2, 28)))
        assertNotNull(f.db.billDao().findOccurrence(last, LocalDate.of(2026, 3, 31)))
        assertNotNull(f.db.billDao().findOccurrence(next, LocalDate.of(2026, 3, 1)))
        val dueDates = f.bills.listPendingUntil(LocalDate.of(2026, 3, 31)).filter { it.billId == last }.map { it.dueDate }
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31").map(LocalDate::parse), dueDates)
    }

    @Test
    fun falhaDeNotificacaoERegistradaETentadaNoMaximoTresVezes() = runTest {
        luz(listOf(0))
        f.clock.current = sp("2026-10-20T09:30")
        f.notifier.available = false
        repeat(5) { f.processor.run() }
        val key = DueEventPlanner.billKey(f.db.billDao().listActiveBills().single().uuid, LocalDate.of(2026, 10, 20), 0)
        val log = f.db.notificationLogDao().findByKey(key)!!
        assertEquals(NotificationStatus.FAILED, log.status)
        assertEquals(ReminderProcessor.MAX_ATTEMPTS, log.attemptCount)
        assertNotNull(log.lastError)

        f.notifier.available = true
        f.processor.run()
        assertTrue("desistiu após 3 tentativas", f.notifier.shown.isEmpty())
    }

    @Test
    fun falhaTemporariaEntregaNaProximaExecucao() = runTest {
        luz(listOf(0))
        f.clock.current = sp("2026-10-20T09:30")
        f.notifier.available = false
        f.processor.run()
        f.notifier.available = true
        f.clock.advance(Duration.ofMinutes(30))
        assertEquals(1, f.processor.run().delivered)
        assertTrue(f.notifier.shown.single().body.contains("vence hoje"))
    }

    @Test
    fun avisosComMaisDeSeteDiasSaoPuladosEAtrasoTemContexto() = runTest {
        luz()
        f.clock.current = sp("2026-10-28T08:00") // 3 e 0 têm mais de 7 dias; o de 21/10 (6 dias e 23 h) não
        val result = f.processor.run()
        assertEquals(2, result.skipped)
        assertEquals(1, result.delivered)
        val body = f.notifier.shown.single().body
        assertTrue(body, body.startsWith("R$ 189,90 está atrasada há 8 dias"))
        assertTrue(body, body.contains("aviso com atraso de 6 dia(s)"))
        assertEquals(BillOccurrenceStatus.PENDING, f.bills.listPendingUntil(LocalDate.of(2026, 10, 28)).first().status)
    }

    @Test
    fun lembretesPessoaisUnicoERecorrente() = runTest {
        val zone = ZoneId.of("America/Sao_Paulo")
        f.clock.current = sp("2026-10-17T10:00")
        val once = f.reminders.create("Ligar para o banco", LocalDateTime.parse("2026-10-17T15:00"), ReminderRepeat.Once, zone)
        val daily = f.reminders.create("Anotar gastos", LocalDateTime.parse("2026-10-17T21:00"), ReminderRepeat.Daily, zone)

        f.clock.current = sp("2026-10-17T15:10")
        f.processor.run()
        assertEquals(listOf("Ligar para o banco"), f.notifier.shown.map { it.body })
        assertFalse("único fica desativado", f.db.reminderDao().getById(once)!!.enabled)

        // Aparelho desligado de 17/10 até 20/10 22:00: um único aviso, depois pula para o próximo futuro.
        f.clock.current = sp("2026-10-20T22:00")
        f.processor.run()
        assertEquals(2, f.notifier.shown.size)
        assertTrue(f.notifier.shown.last().body.contains("aviso com atraso"))
        assertEquals(sp("2026-10-21T21:00"), f.db.reminderDao().getById(daily)!!.scheduledAt)
        f.processor.run()
        assertEquals(2, f.notifier.shown.size)
    }

    @Test
    fun lembreteUnicoNoPassadoERejeitado() = runTest {
        f.clock.current = sp("2026-10-17T10:00")
        val r = runCatching { f.reminders.create("x", LocalDateTime.parse("2026-10-17T09:00"), ReminderRepeat.Once, ZoneId.of("America/Sao_Paulo")) }
        assertTrue(r.isFailure)
    }

    @Test
    fun workerRodaComSucesso() {
        val worker = TestListenableWorkerBuilder<ReminderWorker>(ApplicationProvider.getApplicationContext()).build()
        val result = kotlinx.coroutines.runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
    }
}
