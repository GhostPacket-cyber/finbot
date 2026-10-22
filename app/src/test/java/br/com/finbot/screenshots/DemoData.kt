package br.com.finbot.screenshots

import br.com.finbot.AppContainer
import br.com.finbot.assistant.CardStatus
import br.com.finbot.config.AlertSettings
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.domain.TransactionDraft
import br.com.finbot.importing.CsvImporter
import br.com.finbot.scheduling.ReminderRepeat
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Dados **fictícios** para as capturas do README (pessoa "Ana", valores inventados).
 * Só usado pelo [ScreenshotsTest], num app isolado do Robolectric: nunca toca num aparelho real.
 */
object DemoData {
    suspend fun seed(c: AppContainer) {
        val zone = ZoneId.of("America/Sao_Paulo")
        val today = LocalDate.now(zone)
        val month = YearMonth.from(today)
        c.configRepository.completeOnboarding("Ana", zone.id)
        c.configRepository.setSecurity { it.copy(secureScreen = true, appLock = false, lastExportAt = null, backupReminderDays = 30) }
        c.configRepository.setAlertSettings(AlertSettings())
        c.categoryRepository.ensureDefaults()
        c.accountRepository.ensureDefault()

        suspend fun cat(name: String, kind: TransactionKind = TransactionKind.EXPENSE) = c.categoryRepository.findByName(name, kind)!!.id
        suspend fun add(kind: TransactionKind, cents: Long, desc: String, category: String?, date: LocalDate, source: TransactionSource = TransactionSource.MANUAL) {
            if (date.isAfter(today)) return
            val at = date.atTime(12, 0).atZone(zone).toInstant()
            c.transactionRepository.create(TransactionDraft(kind, cents, desc, category?.let { cat(it, kind) }, null, at), source)
        }

        // Seis meses de histórico, com variação para os gráficos.
        for (back in 5 downTo 0) {
            val m = month.minusMonths(back.toLong())
            val f = 1.0 + (back % 3) * 0.08
            add(TransactionKind.INCOME, 650_000, "Salário", "Salário", m.atDay(5))
            add(TransactionKind.EXPENSE, 180_000, "Aluguel", "Moradia", m.atDay(8))
            add(TransactionKind.EXPENSE, (21_890 * f).toLong(), "Conta de luz", "Moradia", m.atDay(12))
            add(TransactionKind.EXPENSE, 11_990, "Internet fibra", "Moradia", m.atDay(10))
            add(TransactionKind.EXPENSE, 3_990, "Streaming de filmes", "Assinaturas", m.atDay(15))
            add(TransactionKind.EXPENSE, 9_990, "Academia", "Saúde", m.atDay(3))
            listOf(4 to 18_740L, 11 to 23_115L, 18 to 15_980L, 25 to 20_450L).forEach { (d, v) ->
                add(TransactionKind.EXPENSE, (v * f).toLong(), "Supermercado Bom Preço", "Mercado", m.atDay(d))
            }
            listOf(6 to 2_450L, 13 to 3_180L, 20 to 2_790L).forEach { (d, v) -> add(TransactionKind.EXPENSE, v + back * 137, "Corrida de app", "Transporte", m.atDay(d)) }
            listOf(9 to 5_890L, 16 to 7_240L, 23 to 4_650L).forEach { (d, v) -> add(TransactionKind.EXPENSE, v + back * 213, "Restaurante", "Alimentação", m.atDay(d)) }
            if (back % 2 == 0) add(TransactionKind.EXPENSE, 12_000, "Cinema e pipoca", "Lazer", m.atDay(19))
            if (back == 0) add(TransactionKind.INCOME, 85_000, "Freela de design", "Outros", today.minusDays(3))
        }
        // Lazer acima do normal neste mês (para o alerta de gasto fora do padrão).
        add(TransactionKind.EXPENSE, 46_000, "Show e jantar", "Lazer", today.minusDays(2))

        // Contas a pagar e lembrete.
        val bills = c.billRepository
        bills.createBill("Internet fibra", 11_990, cat("Moradia"), today.plusDays(4), recurrenceRule = "MONTHLY")
        bills.createBill("Fatura do cartão", 124_000, null, today.plusDays(9), recurrenceRule = "MONTHLY")
        bills.createBill("IPVA (parcela)", 38_750, null, today.plusDays(16))
        bills.ensureOccurrencesAround(today)
        c.reminderRepository.create("Renovar o seguro do carro", LocalDateTime.of(today.plusDays(6), LocalTime.of(9, 0)), ReminderRepeat.Once, zone)

        // Orçamento de Mercado (o mês corrente passa de 80%).
        c.categoryRepository.setMonthlyBudget(cat("Mercado"), 90_000, month)

        // Extrato importado aguardando revisão.
        val csv = "data;descricao;valor\n" +
            "${today.minusDays(1).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))};PADARIA PAO QUENTE;-18,40\n" +
            "${today.minusDays(1).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))};POSTO CENTRAL;-150,00\n" +
            "${today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))};PIX RECEBIDO CARLOS;250,00\n"
        val preview = c.importRepository.preview(CsvImporter.parse(csv, CsvImporter.suggestMapping(csv)!!), zone)
        c.importRepository.commit(preview, preview.candidates.map { it.index }.toSet(), zone, "extrato-demo.csv")

        // Conversa no chat: um registro confirmado, um resumo e um cartão ainda aberto.
        val chat = c.chatAssistant
        chat.send("gastei 45,90 no mercado")
        chat.observe().first().firstOrNull { it.card?.status == CardStatus.PENDING }?.card?.let { chat.confirm(it.actionId) }
        chat.send("/mes")
        c.alertProcessor.run()
        chat.send("paguei 32 de uber ontem")
    }
}
