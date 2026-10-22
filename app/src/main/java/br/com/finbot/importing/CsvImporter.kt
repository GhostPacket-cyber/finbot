package br.com.finbot.importing

import br.com.finbot.assistant.CategorySuggester
import br.com.finbot.data.TransactionKind
import br.com.finbot.domain.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

enum class CsvDateFormat(val pattern: String, val label: String) {
    DMY("dd/MM/uuuu", "dd/mm/aaaa"),
    DMY_SHORT("dd/MM/uu", "dd/mm/aa"),
    ISO("uuuu-MM-dd", "aaaa-mm-dd");

    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)

    fun parse(text: String): LocalDate? = try {
        LocalDate.parse(text.trim().take(10).let { if (this == DMY_SHORT) it.take(8) else it }, formatter)
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * Como ler as colunas de um CSV. Valor único ([amountColumn], negativo = despesa) ou
 * colunas separadas de débito/crédito. Índices começam em 0.
 */
data class CsvMapping(
    val separator: Char,
    val skipLines: Int,
    val dateColumn: Int,
    val descriptionColumn: Int,
    val amountColumn: Int? = null,
    val debitColumn: Int? = null,
    val creditColumn: Int? = null,
    val dateFormat: CsvDateFormat = CsvDateFormat.DMY,
    /** Alguns bancos mostram despesas como positivas: inverte o sinal da coluna de valor. */
    val invertSign: Boolean = false,
    /** Linhas finais a ignorar (rodapé com saldo/totais). */
    val skipFooterLines: Int = 0,
) {
    val isValid: Boolean
        get() = dateColumn >= 0 && descriptionColumn >= 0 && (amountColumn != null || debitColumn != null || creditColumn != null)
}

object CsvReader {
    /** Separador mais frequente fora de aspas nas primeiras linhas: `;`, `,` ou tabulação. */
    fun detectSeparator(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(15).toList()
        return listOf(';', ',', '\t').maxBy { sep -> sample.sumOf { line -> countOutsideQuotes(line, sep) } }
    }

    private fun countOutsideQuotes(line: String, sep: Char): Int {
        var quoted = false
        var n = 0
        line.forEach { c -> if (c == '"') quoted = !quoted else if (c == sep && !quoted) n++ }
        return n
    }

    fun read(text: String, separator: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val src = text.removePrefix(Char(0xFEFF).toString())
        while (i < src.length) {
            val c = src[i]
            when {
                quoted && c == '"' && i + 1 < src.length && src[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == separator -> { row += cell.toString().trim(); cell.clear() }
                !quoted && c == '\n' -> { row += cell.toString().trim(); cell.clear(); rows += row; row = mutableListOf() }
                !quoted && c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString().trim(); rows += row }
        return rows
    }
}

object CsvImporter {
    private val DATE_HEADERS = listOf("data", "date", "dt")
    private val DESCRIPTION_HEADERS = listOf("descricao", "description", "historico", "lancamento", "memo", "estabelecimento", "detalhe", "titulo", "title", "identificador")
    private val AMOUNT_HEADERS = listOf("valor", "amount", "quantia", "montante")
    private val DEBIT_HEADERS = listOf("debito", "debit", "saida", "saidas")
    private val CREDIT_HEADERS = listOf("credito", "credit", "entrada", "entradas")

    /** Sugere o mapeamento a partir do cabeçalho (primeiras 10 linhas). null se não reconhecer. */
    fun suggestMapping(text: String): CsvMapping? {
        val separator = CsvReader.detectSeparator(text)
        val rows = CsvReader.read(text, separator)
        for ((index, row) in rows.take(10).withIndex()) {
            val names = row.map { CategorySuggester.normalize(it) }
            fun find(keys: List<String>, exclude: Set<Int> = emptySet()) =
                names.indexOfFirst { n -> keys.any { k -> n == k || n.startsWith("$k ") || n.endsWith(" $k") } }.takeIf { it >= 0 && it !in exclude }
            val date = find(DATE_HEADERS) ?: continue
            val description = find(DESCRIPTION_HEADERS, setOf(date)) ?: continue
            val amount = find(AMOUNT_HEADERS, setOf(date, description))
            val debit = find(DEBIT_HEADERS)
            val credit = find(CREDIT_HEADERS)
            if (amount == null && debit == null && credit == null) continue
            val firstData = rows.drop(index + 1).firstOrNull { it.size > date && it[date].isNotBlank() }?.get(date).orEmpty()
            val format = CsvDateFormat.entries.firstOrNull { it.parse(firstData) != null } ?: CsvDateFormat.DMY
            return CsvMapping(separator, index + 1, date, description, amount, if (amount == null) debit else null, if (amount == null) credit else null, format)
        }
        return null
    }

    fun parse(text: String, mapping: CsvMapping): ParsedStatement {
        require(mapping.isValid) { "Mapeamento incompleto" }
        val rows = CsvReader.read(text, mapping.separator).dropLastWhile { r -> r.all { it.isBlank() } }
            .let { it.take(maxOf(0, it.size - mapping.skipFooterLines)) }
        val parsed = mutableListOf<Triple<LocalDate, Long, String>>() // data, valor com sinal, descrição
        val errors = mutableListOf<RowError>()

        rows.forEachIndexed { index, row ->
            if (index < mapping.skipLines || row.all { it.isBlank() }) return@forEachIndexed
            val line = index + 1
            val date = row.getOrNull(mapping.dateColumn)?.let(mapping.dateFormat::parse)
            val signed = signedAmount(row, mapping)
            val amountBlank = listOfNotNull(mapping.amountColumn, mapping.debitColumn, mapping.creditColumn)
                .all { row.getOrNull(it).isNullOrBlank() }
            when {
                // Linha informativa ("SALDO ANTERIOR" sem valor) ou rodapé ("Saldo final", "Total"): ignorada.
                amountBlank || (date == null && signed == null) -> Unit
                date == null -> errors += RowError(line, "data inválida: ${row.getOrNull(mapping.dateColumn).orEmpty()}")
                signed == null -> errors += RowError(line, "valor inválido")
                signed == 0L -> errors += RowError(line, "valor zero")
                kotlin.math.abs(signed) > Money.MAX_CENTS -> errors += RowError(line, "valor acima do limite")
                else -> {
                    val description = row.getOrNull(mapping.descriptionColumn).orEmpty().replace(Regex("\\s+"), " ").take(120)
                        .ifBlank { "Lançamento importado" }
                    parsed += Triple(date, signed, description)
                }
            }
        }

        val items = DedupKeys.withOccurrence(parsed) { (d, v, desc) -> "$d|$v|${CategorySuggester.normalize(desc)}" }
            .map { (t, occurrence) ->
                val (date, signed, description) = t
                val kind = if (signed < 0) TransactionKind.EXPENSE else TransactionKind.INCOME
                val cents = kotlin.math.abs(signed)
                StatementItem(date, kind, cents, description, DedupKeys.csv(date, kind, cents, description, occurrence))
            }
        return ParsedStatement(StatementFormat.CSV, items, errors)
    }

    private fun signedAmount(row: List<String>, m: CsvMapping): Long? {
        m.amountColumn?.let { col ->
            val v = parseAmount(row.getOrNull(col).orEmpty()) ?: return null
            return if (m.invertSign) -v else v
        }
        val debit = m.debitColumn?.let { parseAmount(row.getOrNull(it).orEmpty()) }?.let { kotlin.math.abs(it) }
        val credit = m.creditColumn?.let { parseAmount(row.getOrNull(it).orEmpty()) }?.let { kotlin.math.abs(it) }
        return when {
            debit != null && debit != 0L -> -debit
            credit != null && credit != 0L -> credit
            debit == 0L || credit == 0L -> 0L
            else -> null
        }
    }

    /**
     * Valor no formato dos bancos: "R$ -1.234,56", "-1234.56", "(1.234,56)" (negativo),
     * "1.234,56 D" (débito) / "C" (crédito). Retorna centavos com sinal.
     */
    fun parseAmount(raw: String): Long? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        var negative = false
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true
            text = text.substring(1, text.length - 1)
        }
        val suffix = Regex("\\s*([DdCc])$").find(text)
        if (suffix != null) {
            if (suffix.groupValues[1].equals("D", ignoreCase = true)) negative = !negative
            text = text.removeRange(suffix.range)
        }
        val cents = Money.parseCents(text) ?: return null
        return if (negative) -kotlin.math.abs(cents) else cents
    }
}
