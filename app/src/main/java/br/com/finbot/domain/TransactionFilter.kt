package br.com.finbot.domain

import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Períodos rápidos da tela de lançamentos, sempre no fuso do usuário. */
enum class PeriodPreset(val label: String) {
    THIS_MONTH("Este mês"),
    LAST_MONTH("Mês passado"),
    LAST_30_DAYS("Últimos 30 dias"),
    THIS_YEAR("Este ano"),
    ALL("Tudo");

    /** null = sem limite (período "Tudo"). */
    fun range(today: LocalDate, zone: ZoneId): InstantRange? = when (this) {
        THIS_MONTH -> Periods.month(YearMonth.from(today), zone)
        LAST_MONTH -> Periods.month(YearMonth.from(today).minusMonths(1), zone)
        // Hoje mais os 29 dias anteriores.
        LAST_30_DAYS -> InstantRange(
            today.minusDays(29).atStartOfDay(zone).toInstant(),
            today.plusDays(1).atStartOfDay(zone).toInstant(),
        )
        THIS_YEAR -> InstantRange(
            today.withDayOfYear(1).atStartOfDay(zone).toInstant(),
            today.withDayOfYear(1).plusYears(1).atStartOfDay(zone).toInstant(),
        )
        ALL -> null
    }
}

/** Critérios da lista de lançamentos. Campos null = sem filtro. */
data class TransactionFilter(
    val period: PeriodPreset = PeriodPreset.THIS_MONTH,
    val kind: TransactionKind? = null,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val source: TransactionSource? = null,
    val search: String = "",
) {
    val isDefault: Boolean get() = this == TransactionFilter()

    /** Parâmetros já prontos para a consulta SQL (intervalo e padrão LIKE escapado). */
    fun toQuery(today: LocalDate, zone: ZoneId): FilterQuery {
        val range = period.range(today, zone)
        return FilterQuery(
            start = range?.start,
            end = range?.end,
            kind = kind,
            categoryId = categoryId,
            accountId = accountId,
            source = source,
            likePattern = SearchPattern.like(search),
        )
    }
}

data class FilterQuery(
    val start: Instant?,
    val end: Instant?,
    val kind: TransactionKind?,
    val categoryId: Long?,
    val accountId: Long?,
    val source: TransactionSource?,
    val likePattern: String?,
)

object SearchPattern {
    const val ESCAPE = '\\'

    /**
     * Padrão para `LIKE ... ESCAPE '\'` com o texto do usuário tratado literalmente:
     * `%`, `_` e `\` digitados não viram curingas. Texto vazio = sem busca (null).
     */
    fun like(raw: String): String? {
        val text = raw.trim().take(MAX_LENGTH)
        if (text.isEmpty()) return null
        val escaped = buildString {
            text.forEach { c ->
                if (c == '%' || c == '_' || c == ESCAPE) append(ESCAPE)
                append(c)
            }
        }
        return "%$escaped%"
    }

    const val MAX_LENGTH = 60
}

fun TransactionSource.label(): String = when (this) {
    TransactionSource.MANUAL -> "Manual"
    TransactionSource.CHAT -> "Chat"
    TransactionSource.AI -> "Chat com IA"
    TransactionSource.IMPORT_OFX -> "Extrato OFX"
    TransactionSource.IMPORT_CSV -> "Extrato CSV"
    TransactionSource.BANK_NOTIFICATION -> "Notificação do banco"
    TransactionSource.BILL -> "Conta paga"
}
