package br.com.finbot.ui

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", PT_BR)

fun zoneOrDefault(id: String): ZoneId =
    try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        ZoneId.systemDefault()
    }

fun formatInstant(instant: Instant?, zone: ZoneId): String =
    instant?.atZone(zone)?.format(DATE_TIME) ?: "nunca"
