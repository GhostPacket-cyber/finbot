package br.com.finbot.alerts

import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.config.AlertSettings
import br.com.finbot.data.ChatRole
import br.com.finbot.data.TestFinance
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.domain.TransactionDraft
import br.com.finbot.gemini.AiAvailability
import br.com.finbot.gemini.GeminiOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class AlertProcessorTest {
    private lateinit var f: TestFinance
    private val shown = mutableListOf<Pair<Alert, String>>()
    private val oct = YearMonth.of(2026, 10)

    /** Só a regra em teste ligada, para isolar cada caso. */
    private val none = AlertSettings(budget = false, duplicate = false, subscription = false, unusual = false, weekly = false)

    private fun processor(rewriter: AlertRewriter? = null) = AlertProcessor(
        db = f.db,
        clock = f.clock,
        zone = { f.zone },
        config = { f.config.config.first() },
        notifier = { alert, text -> shown += alert to text; true },
        rewriter = rewriter,
    )

    private fun at(m: Int, d: Int, h: Int = 12, min: Int = 0): Instant = LocalDateTime.of(2026, m, d, h, min).atZone(f.zone).toInstant()

    private suspend fun spend(cents: Long, desc: String, category: String?, t: Instant, status: TransactionStatus = TransactionStatus.CONFIRMED) =
        f.transactions.create(
            TransactionDraft(TransactionKind.EXPENSE, cents, desc, category?.let { f.categoryId(it, TransactionKind.EXPENSE) }, null, t),
            TransactionSource.MANUAL,
            status,
        )

    private suspend fun chatTexts(): List<String> =
        f.db.chatMessageDao().observeRecent(100).first().filter { it.role == ChatRole.ASSISTANT }.map { it.text }

    @Before
    fun setUp() = runTest {
        f = TestFinance() // relógio: sábado 17/10/2026 12:00 em São Paulo
        f.seed()
    }

    @After
    fun tearDown() = f.close()

    @Test
    fun orcamentoAvisaEm80E100UmaVezSo() = runTest {
        f.config.setAlertSettings(none.copy(budget = true))
        val mercado = f.categoryId("Mercado", TransactionKind.EXPENSE)
        // Definido em setembro, vale em outubro.
        f.categories.setMonthlyBudget(mercado, 50_000, oct.minusMonths(1))
        spend(30_000, "Mercado A", "Mercado", at(10, 5))
        val p = processor()
        assertTrue(p.run().isEmpty()) // 60%

        spend(10_000, "Mercado B", "Mercado", at(10, 10))
        spend(99_999, "Sugestão ainda em revisão", "Mercado", at(10, 11), TransactionStatus.PENDING_REVIEW) // não conta
        assertEquals(listOf("alert:budget:$mercado:2026-10:80"), p.run().map { it.key })
        assertTrue(p.run().isEmpty()) // não repete
        assertTrue(chatTexts().single().contains("80%"))

        spend(15_000, "Mercado C", "Mercado", at(10, 12)) // 110%
        assertEquals(listOf("alert:budget:$mercado:2026-10:100"), p.run().map { it.key })
        assertTrue(p.run().isEmpty())
        assertEquals(2, chatTexts().size)
        assertEquals(2, shown.size)
    }

    @Test
    fun saltoDireto100CobreO80() = runTest {
        f.config.setAlertSettings(none.copy(budget = true))
        val lazer = f.categoryId("Lazer", TransactionKind.EXPENSE)
        f.categories.setMonthlyBudget(lazer, 10_000, oct)
        spend(12_000, "Show", "Lazer", at(10, 3))
        assertEquals(1, processor().run().size)
        // O de 80% foi marcado junto e nunca aparece depois.
        assertTrue(processor().run().isEmpty())
        assertEquals(1, chatTexts().size)
    }

    @Test
    fun duplicadaEAssinatura() = runTest {
        f.config.setAlertSettings(none.copy(duplicate = true, subscription = true))
        spend(4_590, "PADARIA SAO JOSE", "Mercado", at(10, 16, 9))
        spend(4_590, "Padaria São José", "Mercado", at(10, 16, 9, 2))
        spend(3_990, "NETFLIX.COM", null, at(9, 15))
        spend(3_990, "PAG*NETFLIX.COM", null, at(10, 15))
        val delivered = processor().run()
        assertEquals(setOf(AlertType.DUPLICATE_CHARGE, AlertType.SUBSCRIPTION), delivered.map { it.type }.toSet())
        assertEquals(2, delivered.size)
        assertTrue(processor().run().isEmpty())
    }

    @Test
    fun assinaturaJaCadastradaComoContaNaoAvisa() = runTest {
        f.config.setAlertSettings(none.copy(subscription = true))
        f.bills.createBill("Netflix", 3_990, null, java.time.LocalDate.of(2026, 10, 15), recurrenceRule = "MONTHLY")
        spend(3_990, "NETFLIX.COM", null, at(9, 15))
        spend(3_990, "NETFLIX.COM", null, at(10, 15))
        assertTrue(processor().run().isEmpty())
    }

    @Test
    fun gastoForaDoPadrao() = runTest {
        f.config.setAlertSettings(none.copy(unusual = true, unusualThresholdPercent = 50))
        listOf(7, 8, 9).forEach { m -> spend(20_000, "Bar", "Lazer", at(m, 10)) }
        spend(30_000, "Bar", "Lazer", at(10, 5)) // exatamente +50%: não avisa
        assertTrue(processor().run().isEmpty())
        spend(100, "Bar", "Lazer", at(10, 6))
        assertEquals(AlertType.UNUSUAL_SPENDING, processor().run().single().type)
    }

    @Test
    fun resumoSemanalNoHorarioUmaVez() = runTest {
        f.config.setAlertSettings(none.copy(weekly = true, weeklyDay = DayOfWeek.MONDAY, weeklyMinuteOfDay = 9 * 60))
        spend(12_345, "Mercado", "Mercado", at(10, 14))
        f.clock.current = at(10, 19, 8, 59) // antes do horário: o slot é da semana passada, atrasado demais
        assertTrue(processor().run().isEmpty())
        f.clock.current = at(10, 19, 9, 0)
        val text = processor().run().single().text
        assertTrue(text, text.contains("12/10 a 18/10") && text.contains("despesas R$ 123,45") && text.contains("Mercado (R$ 123,45)"))
        f.clock.current = at(10, 19, 15, 0)
        assertTrue(processor().run().isEmpty())
    }

    @Test
    fun desligadoNaoAvisaENotificacaoOpcional() = runTest {
        val mercado = f.categoryId("Mercado", TransactionKind.EXPENSE)
        f.categories.setMonthlyBudget(mercado, 10_000, oct)
        spend(9_000, "Mercado", "Mercado", at(10, 5))
        f.config.setAlertSettings(none)
        assertTrue(processor().run().isEmpty())
        f.config.setAlertSettings(none.copy(budget = true, notify = false))
        assertEquals(1, processor().run().size)
        assertEquals(1, chatTexts().size) // o chat sempre recebe
        assertTrue(shown.isEmpty()) // sem notificação do sistema
    }

    @Test
    fun reescritaComNumeroAlteradoFicaComTextoOriginal() = runTest {
        val mercado = f.categoryId("Mercado", TransactionKind.EXPENSE)
        f.categories.setMonthlyBudget(mercado, 10_000, oct)
        spend(9_000, "Mercado", "Mercado", at(10, 5))
        f.config.setAlertSettings(none.copy(budget = true, aiRewrite = true))
        f.gemini.responses += GeminiOutcome.Success("""{"text":"Cuidado! Você já usou 95% do orçamento de Mercado."}""")
        val rewriter = AlertRewriter(f.gemini) { AiAvailability.Ready("k".repeat(30), "m") }
        val alert = processor(rewriter).run().single()
        assertEquals(alert.text, chatTexts().single()) // 95% ≠ 90%: rejeitada
        assertEquals(1, f.gemini.requests.size)
    }
}
