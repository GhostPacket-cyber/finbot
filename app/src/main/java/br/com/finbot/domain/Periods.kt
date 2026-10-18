package br.com.finbot.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Intervalo semiaberto [start, end), em instantes absolutos. */
data class InstantRange(val start: Instant, val end: Instant) {
    init {
        require(start < end) { "Intervalo vazio ou invertido" }
    }

    operator fun contains(instant: Instant): Boolean = instant >= start && instant < end
}

/**
 * Converte períodos de calendário do usuário (no fuso dele) em instantes para consultar o banco,
 * que guarda tudo em UTC. Ex.: outubro em São Paulo = [01/10 00:00 −03, 01/11 00:00 −03).
 */
object Periods {
    fun day(date: LocalDate, zone: ZoneId): InstantRange =
        InstantRange(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())

    fun month(month: YearMonth, zone: ZoneId): InstantRange =
        InstantRange(
            month.atDay(1).atStartOfDay(zone).toInstant(),
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(),
        )

    fun dateOf(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

    fun monthOf(instant: Instant, zone: ZoneId): YearMonth = YearMonth.from(instant.atZone(zone))
}
