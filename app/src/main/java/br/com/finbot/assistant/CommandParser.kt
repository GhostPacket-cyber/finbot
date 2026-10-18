package br.com.finbot.assistant

import br.com.finbot.data.TransactionKind
import br.com.finbot.domain.Money
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

/** Resultado da interpretação determinística de uma mensagem do chat. */
sealed interface ParsedCommand {
    data class CreateTransaction(
        val kind: TransactionKind,
        val amountCents: Long,
        val description: String,
        val categoryName: String,
        val date: LocalDate,
    ) : ParsedCommand

    data object Balance : ParsedCommand
    data object Today : ParsedCommand
    data object Month : ParsedCommand
    data class ListTransactions(val page: Int) : ParsedCommand
    data object Bills : ParsedCommand
    data object Reminders : ParsedCommand
    data object Undo : ParsedCommand
    data object Help : ParsedCommand

    /** Entendemos a intenção, mas falta ou sobra informação. Nada é registrado. */
    data class Clarify(val question: String) : ParsedCommand

    data object Unknown : ParsedCommand
}

/**
 * Parser pt-BR sem IA. Reconhece comandos com barra e frases simples ("gastei 45,90 no mercado").
 * Na dúvida, pede esclarecimento em vez de adivinhar.
 */
object CommandParser {
    const val MAX_LENGTH = 500

    private val SLASH = Regex("^/(\\p{L}+)(?:\\s+(.*))?$", RegexOption.DOT_MATCHES_ALL)

    private val EXPENSE_VERBS = listOf("gastei", "paguei", "comprei", "gasto", "despesa", "gastamos", "pagamos")
    private val INCOME_VERBS = listOf("recebi", "ganhei", "entrou", "receita", "recebemos", "ganhamos")

    // Valor monetário: milhares com ponto (1.234,56), decimal com vírgula ou ponto (45,9 / 1234.56) ou inteiro.
    private val AMOUNT = Regex(
        "(?<![\\p{L}\\d/.,])(?:r\\$\\s*)?(-?(?:\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?))" +
            // Não pode ser seguido de dígito, "/" ou separador+dígito ("12,345" não vira R$ 12).
            "(?:\\s*(?:reais|real|conto|contos))?(?![\\d/]|[.,]\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val RELATIVE_DAY = Regex("\\b(hoje|ontem|anteontem)\\b", RegexOption.IGNORE_CASE)
    // "dia 5" só quando não for o início de uma data completa ("dia 03/10").
    private val DAY_OF_MONTH = Regex("\\bdia\\s+(\\d{1,2})\\b(?!/)", RegexOption.IGNORE_CASE)
    private val NUMERIC_DATE = Regex("(?:\\bdia\\s+)?\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?\\b", RegexOption.IGNORE_CASE)
    private val LEADING_FILLER = Regex(
        "^(?:(?:no|na|nos|nas|num|numa|em|de|do|da|dos|das|com|pro|pra|para|para o|para a|pelo|pela|o|a|um|uma|e)\\s+)+",
        RegexOption.IGNORE_CASE,
    )

    fun parse(input: String, today: LocalDate): ParsedCommand {
        val text = input.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return ParsedCommand.Unknown
        if (text.length > MAX_LENGTH) return ParsedCommand.Clarify("Mensagem muito longa. Envie um lançamento por vez.")

        SLASH.matchEntire(text)?.let { return parseSlash(it.groupValues[1], it.groupValues[2], today) }
        return parseNatural(text, today)
    }

    private fun parseSlash(name: String, args: String, today: LocalDate): ParsedCommand =
        when (CategorySuggester.normalize(name)) {
            "gasto", "despesa" -> parseTransaction(TransactionKind.EXPENSE, args, today, fromCommand = true)
            "receita" -> parseTransaction(TransactionKind.INCOME, args, today, fromCommand = true)
            "saldo" -> ParsedCommand.Balance
            "hoje" -> ParsedCommand.Today
            "mes" -> ParsedCommand.Month
            "gastos", "lancamentos", "extrato" -> ParsedCommand.ListTransactions(args.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1)
            "contas" -> ParsedCommand.Bills
            "lembretes" -> ParsedCommand.Reminders
            "desfazer" -> ParsedCommand.Undo
            "ajuda", "start", "help", "comandos" -> ParsedCommand.Help
            else -> ParsedCommand.Unknown
        }

    private fun parseNatural(text: String, today: LocalDate): ParsedCommand {
        val normalized = CategorySuggester.normalize(text)
        val words = normalized.split(' ')
        // Aceita o verbo no início ou logo após uma data relativa ("ontem paguei 30 de luz").
        val verbIndex = if (words.first() in DATE_WORDS && words.size > 1) 1 else 0
        val verb = words[verbIndex]
        val body = if (verbIndex == 0) stripFirstWord(text) else removeWordAt(text, 1)

        when (verb) {
            in EXPENSE_VERBS -> return parseTransaction(TransactionKind.EXPENSE, body, today, fromCommand = false)
            in INCOME_VERBS -> return parseTransaction(TransactionKind.INCOME, body, today, fromCommand = false)
        }

        // Consultas em linguagem natural (sem verbo de lançamento no início).
        return when {
            normalized in setOf("ajuda", "comandos", "help", "menu") || normalized.contains("o que voce faz") -> ParsedCommand.Help
            normalized.contains("desfazer") -> ParsedCommand.Undo
            "saldo" in words -> ParsedCommand.Balance
            ("mes" in words) && words.any { it in QUERY_WORDS } -> ParsedCommand.Month
            ("hoje" in words) && words.any { it in QUERY_WORDS } -> ParsedCommand.Today
            // Só pedidos explícitos de lista; "lançamentos" no meio de outra frase não basta.
            LIST_QUERIES.any { normalized == it || normalized.startsWith("$it ") } -> ParsedCommand.ListTransactions(1)
            normalized == "contas" || normalized.contains("contas a pagar") -> ParsedCommand.Bills
            normalized == "lembretes" -> ParsedCommand.Reminders
            else -> ParsedCommand.Unknown
        }
    }

    private val LIST_QUERIES = listOf(
        "ultimos", "lancamentos", "meus lancamentos", "ver lancamentos", "mostrar lancamentos", "extrato", "ver extrato",
    )

    private val QUERY_WORDS = setOf("resumo", "gastei", "gastos", "quanto", "gastamos", "relatorio", "como", "balanco")

    private fun parseTransaction(kind: TransactionKind, body: String, today: LocalDate, fromCommand: Boolean): ParsedCommand {
        var rest = body

        // 1) Data (removida do texto antes de procurar o valor, para "dia 5" não virar R$ 5).
        val dateResult = extractDate(rest, today)
        if (dateResult is DateExtraction.Invalid) return ParsedCommand.Clarify(dateResult.message)
        val date = (dateResult as? DateExtraction.Found)?.date ?: today
        if (dateResult is DateExtraction.Found) rest = rest.removeRange(dateResult.range)

        // 2) Valor: exatamente um.
        val amounts = AMOUNT.findAll(rest).toList()
        val noun = if (kind == TransactionKind.EXPENSE) "gasto" else "receita"
        if (amounts.isEmpty()) {
            val example = if (kind == TransactionKind.EXPENSE) "gastei 45,90 no mercado" else "recebi 2.500 de salário"
            return ParsedCommand.Clarify("Faltou o valor. Exemplo: $example")
        }
        if (amounts.size > 1) {
            return ParsedCommand.Clarify("Encontrei mais de um valor. Registre um $noun por vez.")
        }
        val match = amounts.single()
        val cents = Money.parseCents(match.groupValues[1])
            ?: return ParsedCommand.Clarify("Não entendi o valor \"${match.value.trim()}\". Use, por exemplo, 45,90.")
        if (cents <= 0) return ParsedCommand.Clarify("O valor precisa ser maior que zero.")
        if (cents > Money.MAX_CENTS) return ParsedCommand.Clarify("Valor acima do limite de ${Money.format(Money.MAX_CENTS)}.")
        rest = rest.removeRange(match.range)

        // 3) Descrição: o que sobrou, sem preposições iniciais.
        val description = cleanDescription(rest)
            .ifEmpty { if (kind == TransactionKind.EXPENSE) "Gasto" else "Receita" }
        if (fromCommand && description.length > 120) return ParsedCommand.Clarify("Descrição muito longa (máx. 120 caracteres).")

        return ParsedCommand.CreateTransaction(
            kind = kind,
            amountCents = cents,
            description = description.take(120),
            categoryName = CategorySuggester.suggest(kind, description),
            date = date,
        )
    }

    private sealed interface DateExtraction {
        data class Found(val date: LocalDate, val range: IntRange) : DateExtraction
        data class Invalid(val message: String) : DateExtraction
        data object None : DateExtraction
    }

    private fun extractDate(text: String, today: LocalDate): DateExtraction {
        RELATIVE_DAY.find(text)?.let { m ->
            val date = when (m.groupValues[1].lowercase()) {
                "hoje" -> today
                "ontem" -> today.minusDays(1)
                else -> today.minusDays(2)
            }
            return DateExtraction.Found(date, m.range)
        }
        DAY_OF_MONTH.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            if (day !in 1..31) return DateExtraction.Invalid("Dia inválido: $day.")
            // "dia 5" = o dia 5 mais recente que não esteja no futuro.
            val month = if (day <= today.dayOfMonth) YearMonth.from(today) else YearMonth.from(today).minusMonths(1)
            if (day > month.lengthOfMonth()) return DateExtraction.Invalid("O mês ${month.monthValue} não tem dia $day.")
            return DateExtraction.Found(month.atDay(day), m.range)
        }
        NUMERIC_DATE.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val monthValue = m.groupValues[2].toInt()
            val yearText = m.groupValues[3]
            return try {
                val date = if (yearText.isEmpty()) {
                    LocalDate.of(today.year, monthValue, day).let { if (it.isAfter(today)) it.minusYears(1) else it }
                } else {
                    val year = if (yearText.length == 2) 2000 + yearText.toInt() else yearText.toInt()
                    LocalDate.of(year, monthValue, day)
                }
                DateExtraction.Found(date, m.range)
            } catch (_: DateTimeException) {
                DateExtraction.Invalid("Data inválida: ${m.value}.")
            }
        }
        return DateExtraction.None
    }

    private val DATE_WORDS = setOf("hoje", "ontem", "anteontem")

    private fun stripFirstWord(text: String): String = text.substringAfter(' ', "")

    private fun removeWordAt(text: String, index: Int): String =
        text.split(' ').filterIndexed { i, _ -> i != index }.joinToString(" ")

    private fun cleanDescription(text: String): String {
        var s = text.replace(Regex("\\s+"), " ").trim().trim(',', '.', '-', ';', ':').trim()
        repeat(3) { s = LEADING_FILLER.replace(s, "").trim() }
        s = s.replace(Regex("\\s+(?:no|na|de|do|da|em|por|e|com)$", RegexOption.IGNORE_CASE), "").trim()
        return s.replaceFirstChar { it.uppercase() }
    }
}
