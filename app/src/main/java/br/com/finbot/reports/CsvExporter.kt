package br.com.finbot.reports

import br.com.finbot.data.TransactionKind
import br.com.finbot.data.dao.TransactionRow
import br.com.finbot.domain.label
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CSV no formato que o Excel e o Google Planilhas em português abrem direto:
 * UTF-8 com BOM, separador `;`, decimal com vírgula, datas dd/mm/aaaa e cabeçalhos em pt-BR.
 */
object CsvExporter {
    /** Marca de ordem de bytes do UTF-8: faz o Excel reconhecer acentos. Gerada por código (não literal no fonte). */
    val BOM: String = Char(0xFEFF).toString()
    const val SEPARATOR = ';'
    val HEADER = listOf("Data", "Tipo", "Descrição", "Categoria", "Conta", "Valor", "Origem", "Observação")
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun export(rows: List<TransactionRow>, zone: ZoneId): String = buildString {
        append(BOM)
        appendLine(HEADER.joinToString(SEPARATOR.toString()) { field(it) })
        rows.forEach { r ->
            val t = r.transaction
            val cells = listOf(
                t.occurredAt.atZone(zone).toLocalDate().format(DATE),
                if (t.kind == TransactionKind.EXPENSE) "Despesa" else "Receita",
                t.description,
                r.categoryName ?: "Sem categoria",
                r.accountName.orEmpty(),
                amount(t.amountCents),
                t.source.label(),
                t.note.orEmpty(),
            )
            appendLine(cells.joinToString(SEPARATOR.toString()) { field(it) })
        }
    }

    /** "1234,56": sem "R$" nem separador de milhar, para a planilha reconhecer como número. */
    fun amount(cents: Long): String {
        require(cents >= 0)
        return "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
    }

    /**
     * Escapa um campo: aspas quando há separador, aspas ou quebra de linha; e neutraliza injeção de
     * fórmula (texto começando com = + - @ ou tab/CR vira texto com ' na frente).
     */
    fun field(raw: String): String {
        val safe = if (raw.isNotEmpty() && raw[0] in FORMULA_STARTS) "'$raw" else raw
        val needsQuotes = safe.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')

    /** Leitura simples (para testes e verificação): respeita aspas e `;`. Ignora o BOM. */
    fun parse(csv: String): List<List<String>> {
        val text = csv.removePrefix(BOM)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == SEPARATOR -> { row += cell.toString(); cell.clear() }
                !quoted && c == '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf() }
                !quoted && c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
}
