package br.com.finbot.scheduling

import br.com.finbot.data.ShortMonthPolicy
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Datas de vencimento de contas. O dia-âncora vem sempre do primeiro vencimento,
 * então 31/jan → 28/fev → 31/mar (não "escorrega" para 28).
 */
object Recurrence {
    const val MONTHLY = "MONTHLY"

    /** Vencimento da n-ésima ocorrência mensal (n = 0 é o primeiro). */
    fun monthlyDate(firstDue: LocalDate, index: Long, policy: ShortMonthPolicy): LocalDate {
        val month = YearMonth.from(firstDue).plusMonths(index)
        val day = firstDue.dayOfMonth
        if (day <= month.lengthOfMonth()) return month.atDay(day)
        return when (policy) {
            ShortMonthPolicy.LAST_DAY_OF_MONTH -> month.atEndOfMonth()
            ShortMonthPolicy.FIRST_DAY_OF_NEXT_MONTH -> month.plusMonths(1).atDay(1)
        }
    }

    /** Vencimentos em [from, to] (inclusive). Conta única: só o primeiro vencimento. */
    fun dueDates(firstDue: LocalDate, rule: String?, policy: ShortMonthPolicy, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        if (rule == null) return if (!firstDue.isBefore(from) && !firstDue.isAfter(to)) listOf(firstDue) else emptyList()
        require(rule == MONTHLY) { "Recorrência não suportada: $rule" }
        // Começa um mês antes do necessário: a regra do 1º dia do mês seguinte pode cair dentro do intervalo.
        var index = maxOf(0L, ChronoUnit.MONTHS.between(YearMonth.from(firstDue), YearMonth.from(from)) - 1)
        val result = mutableListOf<LocalDate>()
        while (true) {
            val date = monthlyDate(firstDue, index, policy)
            if (date.isAfter(to)) break
            if (!date.isBefore(from)) result += date
            index++
        }
        return result
    }
}

/**
 * Repetição de lembretes pessoais, gravada como texto em `reminders.recurrence_rule`:
 * null (uma vez), "DAILY", "WEEKLY" ou "MONTHLY:<dia>" (dia-âncora; meses curtos usam o último dia).
 */
sealed interface ReminderRepeat {
    data object Once : ReminderRepeat
    data object Daily : ReminderRepeat
    data object Weekly : ReminderRepeat
    data class Monthly(val anchorDay: Int) : ReminderRepeat {
        init {
            require(anchorDay in 1..31)
        }
    }

    fun encode(): String? = when (this) {
        Once -> null
        Daily -> "DAILY"
        Weekly -> "WEEKLY"
        is Monthly -> "MONTHLY:$anchorDay"
    }

    val label: String
        get() = when (this) {
            Once -> "Uma vez"
            Daily -> "Todo dia"
            Weekly -> "Toda semana"
            is Monthly -> "Todo mês (dia $anchorDay)"
        }

    /** Próxima ocorrência estritamente depois de [after], a partir de [current]. null para "uma vez". */
    fun nextAfter(current: LocalDateTime, after: LocalDateTime): LocalDateTime? {
        if (this == Once) return null
        var next = step(current)
        // Pula ocorrências perdidas (ex.: aparelho desligado vários dias): só a próxima futura importa.
        var guard = 0
        while (!next.isAfter(after)) {
            next = step(next)
            check(++guard < 100_000) { "Repetição sem progresso" }
        }
        return next
    }

    private fun step(from: LocalDateTime): LocalDateTime = when (this) {
        Once -> from
        Daily -> from.plusDays(1)
        Weekly -> from.plusWeeks(1)
        is Monthly -> {
            val month = YearMonth.from(from).plusMonths(1)
            val day = minOf(anchorDay, month.lengthOfMonth())
            LocalDateTime.of(month.atDay(day), from.toLocalTime())
        }
    }

    companion object {
        fun decode(rule: String?): ReminderRepeat = when {
            rule == null -> Once
            rule == "DAILY" -> Daily
            rule == "WEEKLY" -> Weekly
            rule.startsWith("MONTHLY:") -> Monthly(rule.removePrefix("MONTHLY:").toInt())
            else -> throw IllegalArgumentException("Repetição desconhecida: $rule")
        }
    }
}

/** Dias relativos ao vencimento: positivo = antes, 0 = no dia, negativo = depois (atraso). */
object ReminderOffsets {
    val CHOICES = listOf(3, 1, 0, -1, -3)
    val DEFAULT = listOf(3, 0)

    fun parse(text: String): List<Int> =
        text.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in -30..30 }.distinct().sortedDescending()

    fun encode(offsets: Collection<Int>): String = offsets.distinct().sortedDescending().joinToString(",")

    fun label(offset: Int): String = when {
        offset > 1 -> "$offset dias antes"
        offset == 1 -> "1 dia antes"
        offset == 0 -> "No dia"
        offset == -1 -> "1 dia depois"
        else -> "${-offset} dias depois"
    }
}
