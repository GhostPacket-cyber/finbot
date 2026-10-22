package br.com.finbot.assistant

import br.com.finbot.data.TransactionKind
import br.com.finbot.data.dao.BillDueRow
import br.com.finbot.data.dao.CategoryTotal
import br.com.finbot.data.dao.TransactionWithCategory
import br.com.finbot.data.entity.ReminderEntity
import br.com.finbot.data.repository.MonthlyReport
import br.com.finbot.data.repository.PeriodSummary
import br.com.finbot.reports.ReportMath
import br.com.finbot.domain.Money
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Textos das respostas do assistente. Todos os números chegam já calculados pelo SQLite. */
object ChatTexts {
    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR)
    private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", PT_BR)
    private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", PT_BR)

    val HELP = """
        Posso registrar e consultar suas finanças. Exemplos:
        • gastei 45,90 no mercado
        • recebi 2.500 de salário
        • paguei 120 de luz ontem
        • /gasto 35 almoço · /receita 2500 salário
        • /saldo · /hoje · /mes · /gastos
        • /contas · /lembretes · /desfazer
        Nada é registrado sem você tocar em Confirmar.
    """.trimIndent()

    /** Resposta quando a IA foi consultada e não identificou lançamento nem consulta financeira. */
    val UNKNOWN_AI = """
        Não identifiquei um gasto, uma receita ou uma consulta nessa mensagem. Exemplos:
        • comprei um presente de quarenta reais
        • gastei 12 no café e 35,50 no almoço
        • quanto gastei este mês?
    """.trimIndent()

    val UNKNOWN = """
        Não entendi. Sem a IA, eu reconheço frases curtas como:
        • gastei 45,90 no mercado
        • recebi 2.500 de salário
        • /saldo ou /mes
        Envie /ajuda para ver todos os comandos.
    """.trimIndent()

    fun kindNoun(kind: TransactionKind) = if (kind == TransactionKind.EXPENSE) "despesa" else "receita"

    fun date(date: LocalDate): String = date.format(DATE)

    fun relativeDate(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "hoje (${date.format(SHORT_DATE)})"
        today.minusDays(1) -> "ontem (${date.format(SHORT_DATE)})"
        else -> date.format(DATE)
    }

    fun createPrompt(p: ActionPayload.CreateTransaction, today: LocalDate): String =
        "Registrar ${kindNoun(p.kind)} de ${Money.format(p.amountCents)}?\n" +
            "${p.description} · ${p.categoryName ?: "Sem categoria"} · ${relativeDate(p.date, today)}"

    fun deletePrompt(p: ActionPayload.DeleteTransaction): String =
        "Excluir o último lançamento do chat?\n" +
            "${p.description} · ${Money.format(p.amountCents)} · ${date(p.date)}"

    fun created(p: ActionPayload.CreateTransaction, balanceCents: Long): String =
        "${kindNoun(p.kind).replaceFirstChar { it.uppercase() }} registrada: ${Money.format(p.amountCents)} " +
            "(${p.categoryName ?: "Sem categoria"}).\n${balanceLine(balanceCents)}"

    fun deleted(p: ActionPayload.DeleteTransaction, balanceCents: Long): String =
        "Lançamento excluído: ${p.description} (${Money.format(p.amountCents)}).\n${balanceLine(balanceCents)}"

    const val AI_HINT = "Para frases livres, vários gastos numa mensagem e áudio, ative a IA em Ajustes."
    const val AUDIO_NEEDS_AI =
        "Mensagens de voz precisam da IA (Gemini), que está desativada ou sem chave. Ative em Ajustes, ou digite: gastei 45,90 no mercado."
    const val NOT_SUPPORTED_YET =
        "Para cadastrar contas a pagar e lembretes, use a aba Contas. Pelo chat eu registro gastos e receitas."

    fun audioLabel(seconds: Int) = "🎤 Mensagem de voz ($seconds s)"

    fun heard(transcript: String) = "Entendi: “$transcript”"

    fun batchPrompt(batch: ActionPayload.CreateBatch): String = buildString {
        append("Registrar ${batch.items.size} lançamentos?\n")
        batch.items.forEach { append("• ${it.description}: ${Money.format(it.amountCents)} (${kindNoun(it.kind)})\n") }
        append(batchTotals(batch))
    }

    fun batchTotals(batch: ActionPayload.CreateBatch): String = listOfNotNull(
        batch.expenseCents.takeIf { it > 0 }?.let { "Despesas: ${Money.format(it)}" },
        batch.incomeCents.takeIf { it > 0 }?.let { "Receitas: ${Money.format(it)}" },
    ).joinToString(" · ")

    fun batchCreated(batch: ActionPayload.CreateBatch, balanceCents: Long): String =
        "${batch.items.size} lançamentos registrados. ${batchTotals(batch)}.\n${balanceLine(balanceCents)}"

    const val ALREADY_RESOLVED = "Esta confirmação já foi usada, cancelada ou expirou. Nada foi alterado."
    const val CANCELLED = "Cancelado. Nada foi registrado."
    const val NOTHING_TO_UNDO = "Não há lançamento do chat para desfazer."

    fun balanceLine(cents: Long) = "Saldo calculado: ${Money.format(cents)}"

    fun balance(cents: Long) =
        "${balanceLine(cents)}\nÉ a soma das receitas menos as despesas registradas no FinBot, não o saldo da sua conta bancária."

    fun today(summary: PeriodSummary, date: LocalDate): String =
        "Hoje, ${date.format(SHORT_DATE)}:\n" + summaryBody(summary)

    fun month(summary: PeriodSummary, date: LocalDate): String {
        val name = date.format(MONTH).replaceFirstChar { it.uppercase() }
        return "$name:\n" + summaryBody(summary)
    }

    /** Resumo mensal do chat com os mesmos números da aba Relatórios. */
    fun month(summary: PeriodSummary, report: MonthlyReport, date: LocalDate): String {
        val lines = mutableListOf(month(summary, date))
        if (summary.transactionCount > 0) {
            report.expenseVariation?.let { lines += "Despesas vs mês anterior: ${ReportMath.formatPercent(it)}" }
            report.dailyAverageExpense?.let { lines += "Média diária de despesas: ${Money.format(it)}" }
        }
        if (report.billsPending.count > 0) {
            lines += "Contas pendentes (previsto, fora das despesas): ${report.billsPending.count} · ${Money.format(report.billsPending.cents)}"
        }
        return lines.joinToString("\n")
    }

    private fun summaryBody(s: PeriodSummary): String {
        if (s.transactionCount == 0) return "Nenhum lançamento confirmado no período."
        val lines = mutableListOf(
            "Receitas: ${Money.format(s.incomeCents)}",
            "Despesas: ${Money.format(s.expenseCents)}",
            "Resultado: ${Money.format(s.netCents)}",
        )
        if (s.expensesByCategory.isNotEmpty()) {
            lines += "Maiores despesas:"
            lines += s.expensesByCategory.take(5).map { categoryLine(it) }
        }
        return lines.joinToString("\n")
    }

    private fun categoryLine(c: CategoryTotal) = "• ${c.categoryName ?: "Sem categoria"}: ${Money.format(c.totalCents)}"

    fun transactionList(items: List<TransactionWithCategory>, page: Int, zone: ZoneId): String {
        if (items.isEmpty()) return if (page == 1) "Nenhum lançamento confirmado ainda." else "Não há mais lançamentos."
        val header = if (page == 1) "Últimos lançamentos:" else "Lançamentos (página $page):"
        return header + "\n" + items.joinToString("\n") { row ->
            val t = row.transaction
            val sign = if (t.kind == TransactionKind.EXPENSE) "−" else "+"
            "${t.occurredAt.atZone(zone).toLocalDate().format(SHORT_DATE)} $sign${Money.format(t.amountCents)} " +
                "${t.description} (${row.categoryName ?: "Sem categoria"})"
        }
    }

    fun bills(items: List<BillDueRow>): String =
        if (items.isEmpty()) {
            "Nenhuma conta pendente. Cadastre na aba Contas › + Conta."
        } else {
            "Contas pendentes:\n" + items.joinToString("\n") { "• ${it.title}: ${Money.format(it.amountCents)} — vence ${date(it.dueDate)}" }
        }

    fun reminders(items: List<ReminderEntity>, zone: ZoneId): String =
        if (items.isEmpty()) {
            "Nenhum lembrete ativo. Cadastre na aba Contas."
        } else {
            "Lembretes ativos:\n" + items.joinToString("\n") { "• ${it.title} — ${date(it.scheduledAt.atZone(zone).toLocalDate())}" }
        }
}
