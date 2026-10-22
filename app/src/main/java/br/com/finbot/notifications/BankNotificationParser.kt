package br.com.finbot.notifications

import br.com.finbot.data.TransactionKind
import br.com.finbot.domain.Money
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class BankEventType(val kind: TransactionKind) {
    PURCHASE_CREDIT(TransactionKind.EXPENSE),
    PURCHASE_DEBIT(TransactionKind.EXPENSE),
    PIX_SENT(TransactionKind.EXPENSE),
    PAYMENT_SENT(TransactionKind.EXPENSE),
    PIX_RECEIVED(TransactionKind.INCOME),
    TRANSFER_RECEIVED(TransactionKind.INCOME),
}

/** Campos extraídos de uma notificação. O texto bruto nunca é guardado. */
data class BankNotificationEvent(
    val type: BankEventType,
    val amountCents: Long,
    /** Estabelecimento ou contraparte. */
    val counterpart: String,
    val occurredAt: Instant,
) {
    val kind: TransactionKind get() = type.kind

    /** Descrição do lançamento sugerido. */
    val description: String
        get() = when (type) {
            BankEventType.PIX_SENT -> "Pix para $counterpart"
            BankEventType.PIX_RECEIVED -> "Pix de $counterpart"
            BankEventType.TRANSFER_RECEIVED -> "Transferência de $counterpart"
            else -> counterpart
        }.take(120)
}

/**
 * Padrões de notificações de compra, Pix e transferência dos apps de banco brasileiros.
 * Puro e determinístico: sem IA. Se nada casar, a notificação é ignorada.
 * Os padrões cobrem as formas usuais; os testes usam só amostras sintéticas.
 */
object BankNotificationParser {
    private const val AMOUNT = """R\$\s?(?<valor>-?\d{1,3}(?:\.\d{3})*,\d{2}|-?\d+,\d{2})"""
    private const val WHO = """(?<quem>[^.;!\n]+?)"""

    /** Onde o nome do estabelecimento/contraparte termina. */
    private const val END =
        """(?=\s+(?:para o|para seu|no seu|no cart|com o|com final|final \d|cart[aã]o|[àa]s \d|em \d{1,2}/\d{1,2}|via |foi |pelo |pela |dia \d)|\s*[.;!]|\s+-\s|$)"""

    /** Data opcional entre o valor e o estabelecimento, como no Itaú ("em 21/10 às 14:32 em PADARIA"). */
    private const val WHEN = """(?:\s+(?:em|no dia)\s+\d{1,2}/\d{1,2}(?:/\d{2,4})?)?(?:,?\s+[àa]s\s+\d{1,2}[:h]\d{2})?"""

    private class Rule(val type: BankEventType, pattern: String) {
        val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE))
    }

    private val RULES: List<Rule> = listOf(
        // Pix e transferências vêm antes: "pagamento" e "compra" aparecem em textos de Pix.
        Rule(BankEventType.PIX_RECEIVED, """(?:voc[eê]\s+)?recebeu\s+um\s+pix\s+(?:no valor\s+)?de\s+$AMOUNT\s+de\s+$WHO$END"""),
        Rule(BankEventType.PIX_RECEIVED, """pix\s+recebido:?\s+(?:de\s+)?$AMOUNT\s+(?:de|enviado por)\s+$WHO$END"""),
        Rule(BankEventType.PIX_RECEIVED, """$WHO\s+te\s+(?:enviou|mandou)\s+(?:um\s+pix\s+de\s+)?$AMOUNT(?:\s+por pix)?$END"""),
        Rule(BankEventType.PIX_SENT, """(?:voc[eê]\s+)?(?:enviou|fez|realizou|transferiu)\s+(?:um\s+)?pix\s+(?:no valor\s+)?de\s+$AMOUNT\s+para\s+$WHO$END"""),
        Rule(BankEventType.PIX_SENT, """pix\s+(?:enviado|realizado|feito)(?:\s+com sucesso)?:?\s+(?:de\s+)?$AMOUNT\s+para\s+$WHO$END"""),
        Rule(BankEventType.PIX_SENT, """transfer[eê]ncia\s+pix\s+(?:de\s+)?$AMOUNT\s+(?:enviada\s+)?para\s+$WHO$END"""),
        Rule(BankEventType.TRANSFER_RECEIVED, """(?:voc[eê]\s+)?recebeu\s+uma\s+transfer[eê]ncia(?:\s+(?:ted|doc))?\s+de\s+$AMOUNT\s+de\s+$WHO$END"""),
        Rule(BankEventType.TRANSFER_RECEIVED, """transfer[eê]ncia\s+recebida:?\s+(?:de\s+)?$AMOUNT\s+de\s+$WHO$END"""),
        Rule(BankEventType.PURCHASE_DEBIT, """compra\s+(?:no\s+)?d[eé]bito\s+(?:aprovada\s+)?(?:de\s+)?$AMOUNT(?:\s+aprovada)?$WHEN\s+(?:em|no|na)\s+$WHO$END"""),
        Rule(BankEventType.PURCHASE_DEBIT, """d[eé]bito\s+de\s+$AMOUNT$WHEN\s+(?:em|no|na)\s+$WHO$END"""),
        Rule(BankEventType.PURCHASE_CREDIT, """compra\s+(?:no\s+cr[eé]dito\s+)?(?:de\s+)?$AMOUNT\s+(?:foi\s+)?aprovada$WHEN\s+(?:em|no|na)\s+$WHO$END"""),
        Rule(BankEventType.PURCHASE_CREDIT, """compra\s+aprovada\b.*?\s(?:de|no valor de)\s+$AMOUNT$WHEN\s+(?:em|no|na|loja:?)\s+$WHO$END"""),
        Rule(BankEventType.PURCHASE_CREDIT, """compra\s+(?:realizada|efetuada)?:?\s*(?:de\s+)?$AMOUNT$WHEN\s+(?:em|no|na)\s+$WHO$END"""),
        Rule(BankEventType.PAYMENT_SENT, """(?:voc[eê]\s+)?pagou\s+$AMOUNT\s+(?:a|para|em|no|na)\s+$WHO$END"""),
    )

    /** Textos que nunca viram lançamento: compra negada, estorno, códigos, agendamentos, faturas. */
    private val REJECT = Regex(
        """recusad|negad|n[aã]o\s+(?:foi\s+)?aprovad|n[aã]o\s+autorizad|cancelad|estorn|devolu|c[oó]digo|senha|token|agendad|programad|fatura|limite\s+dispon|tentativa""",
        RegexOption.IGNORE_CASE,
    )
    private val DATE = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
    private val TIME = Regex("""\b(\d{1,2})[:h](\d{2})\b""")
    private const val MAX_TEXT = 1_000

    /**
     * [texts]: título e textos da notificação (cada um analisado por inteiro e com o título junto).
     * [postedAt]: horário da notificação, usado quando o texto não traz data e hora.
     */
    fun parse(texts: List<String>, postedAt: Instant, zone: ZoneId): BankNotificationEvent? {
        val clean = texts.map { normalize(it) }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return null
        val candidates = (listOf(clean.joinToString(" ")) + clean).distinct()
        for (text in candidates) {
            if (REJECT.containsMatchIn(text)) return null
            for (rule in RULES) {
                val m = rule.regex.find(text) ?: continue
                val cents = m.groups["valor"]?.value?.let { Money.parseCents(it) }?.let { kotlin.math.abs(it) } ?: continue
                if (!Money.isValidAmount(cents)) continue
                val who = cleanCounterpart(m.groups["quem"]?.value.orEmpty()) ?: continue
                return BankNotificationEvent(rule.type, cents, who, occurredAt(text, postedAt, zone))
            }
        }
        return null
    }

    private fun normalize(text: String): String =
        text.take(MAX_TEXT).replace(Regex("""[  ]"""), " ").replace(Regex("""\s+"""), " ").trim()

    private fun cleanCounterpart(raw: String): String? {
        val text = raw.trim().trim('"', '\'', ':', '-', ',').trim()
        if (text.length < 2 || text.none { it.isLetter() }) return null
        return text.take(80)
    }

    /** Data e hora do texto (dd/mm[/aa] e hh:mm) quando existem; senão, o horário da notificação. */
    fun occurredAt(text: String, postedAt: Instant, zone: ZoneId): Instant {
        val posted = postedAt.atZone(zone)
        val date = DATE.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it } ?: posted.year
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { d ->
                // Sem ano no texto: uma data "no futuro" (ex.: 31/12 lida em 01/01) é do ano anterior.
                if (m.groupValues[3].isEmpty() && d.isAfter(posted.toLocalDate().plusDays(1))) d.minusYears(1) else d
            }
        }
        val time = TIME.find(text)?.let { m ->
            runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
        }
        if (date == null && time == null) return postedAt.truncatedTo(ChronoUnit.MINUTES)
        val result = LocalDateTime.of(date ?: posted.toLocalDate(), time ?: posted.toLocalTime().truncatedTo(ChronoUnit.MINUTES))
            .atZone(zone).toInstant()
        // Nunca depois da própria notificação.
        return if (result.isAfter(postedAt)) postedAt.truncatedTo(ChronoUnit.MINUTES) else result
    }
}
