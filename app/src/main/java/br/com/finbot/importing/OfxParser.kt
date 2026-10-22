package br.com.finbot.importing

import br.com.finbot.data.TransactionKind
import br.com.finbot.domain.Money
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Leitor tolerante de OFX 1.x (SGML, tags de valor sem fechamento) e 2.x (XML).
 * Lê `STMTTRN` (TRNTYPE, DTPOSTED, TRNAMT, FITID, NAME, MEMO) e os dados da conta (BANKID, ACCTID).
 */
object OfxParser {
    private val TAG = Regex("<(/?)([A-Za-z0-9._]+)\\s*>([^<]*)")
    private const val MAX_DESCRIPTION = 120

    fun parse(text: String): ParsedStatement {
        val body = text.substring(maxOf(0, text.indexOf('<')))
        val header = HashMap<String, String>()
        val transactions = mutableListOf<Map<String, String>>()
        var current: MutableMap<String, String>? = null

        for (m in TAG.findAll(body)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].uppercase()
            val value = decodeEntities(m.groupValues[3].trim())
            when {
                name == "STMTTRN" && !closing -> {
                    current?.let { transactions += it } // SGML sem </STMTTRN>
                    current = HashMap()
                }
                (name == "STMTTRN" || name == "BANKTRANLIST") && closing -> {
                    current?.let { transactions += it }
                    current = null
                }
                closing || value.isEmpty() -> Unit
                current != null -> current[name] = value
                name in HEADER_FIELDS -> header.putIfAbsent(name, value)
            }
        }
        current?.let { transactions += it }

        val accountId = header["ACCTID"]
        val items = mutableListOf<StatementItem>()
        val errors = mutableListOf<RowError>()
        transactions.forEachIndexed { index, t ->
            val n = index + 1
            val fitId = t["FITID"]
            val cents = t["TRNAMT"]?.let(::parseAmount)
            val date = t["DTPOSTED"]?.let(::parseDate)
            val description = listOfNotNull(t["NAME"], t["MEMO"]).map { it.replace(Regex("\\s+"), " ") }.distinct()
                .joinToString(" · ").take(MAX_DESCRIPTION).ifBlank { t["TRNTYPE"] ?: "Lançamento" }
            when {
                fitId.isNullOrBlank() -> errors += RowError(n, "lançamento sem FITID")
                cents == null -> errors += RowError(n, "valor inválido: ${t["TRNAMT"]}")
                cents == 0L -> errors += RowError(n, "valor zero")
                date == null -> errors += RowError(n, "data inválida: ${t["DTPOSTED"]}")
                kotlin.math.abs(cents) > Money.MAX_CENTS -> errors += RowError(n, "valor acima do limite")
                else -> items += StatementItem(
                    date = date,
                    kind = if (cents < 0) TransactionKind.EXPENSE else TransactionKind.INCOME,
                    amountCents = kotlin.math.abs(cents),
                    description = description,
                    dedupKey = DedupKeys.ofx(accountId, fitId),
                    externalReference = fitId,
                )
            }
        }
        return ParsedStatement(StatementFormat.OFX, items, errors, accountId = accountId, bankId = header["BANKID"])
    }

    private val HEADER_FIELDS = setOf("BANKID", "ACCTID", "CURDEF", "BRANCHID")

    /** DTPOSTED: AAAAMMDD[HHMMSS[.XXX]][[±fuso:NOME]]. Só a data importa (meio-dia local no app). */
    fun parseDate(raw: String): LocalDate? {
        val digits = raw.trim().take(8)
        if (digits.length != 8 || !digits.all { it.isDigit() }) return null
        return try {
            LocalDate.of(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    private val OFX_AMOUNT = Regex("^-?[0-9]+(\\.[0-9]+)?$")

    /** TRNAMT usa ponto decimal ("-1234.50"); alguns bancos brasileiros mandam vírgula. */
    fun parseAmount(raw: String): Long? {
        val text = raw.trim().removePrefix("+").replace(',', '.')
        if (!OFX_AMOUNT.matches(text)) return null
        val value = text.toBigDecimal().stripTrailingZeros()
        if (value.scale() > 2) return null
        return runCatching { value.movePointRight(2).toBigIntegerExact().toLong() }.getOrNull()
    }

    private fun decodeEntities(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
