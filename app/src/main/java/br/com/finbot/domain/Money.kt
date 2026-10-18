package br.com.finbot.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Dinheiro sempre em centavos [Long]. Nada de Double em cálculos.
 * Conversões de texto seguem o formato brasileiro, aceitando também ponto decimal quando não houver ambiguidade.
 */
object Money {
    /** R$ 10.000.000,00: limite de sanidade por lançamento. */
    const val MAX_CENTS: Long = 1_000_000_000L

    private val CLEANUP = Regex("(?i)r\\$|reais|real|brl|\\s| ")
    private val VALID = Regex("^-?[0-9.,]+$")

    /**
     * Converte texto em centavos. Retorna null para texto inválido, ambíguo ou com mais de 2 casas decimais.
     * Exemplos: "R$ 1.234,56" → 123456; "45" → 4500; "45,9" → 4590; "1234.56" → 123456; "1.234" → 123400.
     */
    fun parseCents(text: String): Long? {
        val raw = CLEANUP.replace(text.trim(), "")
        if (raw.isEmpty() || !VALID.matches(raw)) return null
        val negative = raw.startsWith("-")
        val digits = raw.removePrefix("-")
        if (digits.isEmpty() || digits.startsWith(",") || digits.startsWith(".")) return null

        val normalized = normalizeSeparators(digits) ?: return null
        val value = runCatching { BigDecimal(normalized) }.getOrNull() ?: return null
        if (value.scale() > 2) return null
        val cents = runCatching { value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact() }
            .getOrNull() ?: return null
        return if (negative) -cents else cents
    }

    /** Devolve uma string aceita por BigDecimal ("1234.56") ou null se os separadores forem inconsistentes. */
    private fun normalizeSeparators(s: String): String? {
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        return when {
            // Vírgula decimal (padrão BR): pontos antes dela são milhares.
            lastComma >= 0 -> {
                if (s.count { it == ',' } > 1 || lastDot > lastComma) return null
                val intPart = s.substring(0, lastComma)
                val decPart = s.substring(lastComma + 1)
                if (decPart.isEmpty() || decPart.length > 2) return null
                if (!validThousands(intPart, '.')) return null
                intPart.replace(".", "") + "." + decPart
            }
            lastDot >= 0 -> {
                val groups = s.split('.')
                // Um único ponto seguido de 1–2 dígitos é decimal ("45.90"); caso contrário, milhares ("1.234").
                if (groups.size == 2 && groups[1].length in 1..2) {
                    s
                } else if (validThousands(s, '.')) {
                    s.replace(".", "")
                } else {
                    null
                }
            }
            else -> s
        }
    }

    private fun validThousands(s: String, sep: Char): Boolean {
        if (!s.contains(sep)) return s.isNotEmpty()
        val groups = s.split(sep)
        return groups.first().length in 1..3 && groups.drop(1).all { it.length == 3 }
    }

    /** "R$ 1.234,56"; negativos como "-R$ 1.234,56". */
    fun format(cents: Long): String {
        val negative = cents < 0
        val abs = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(cents)
        val reais = (abs / 100).toString().reversed().chunked(3).joinToString(".").reversed()
        val centavos = (abs % 100).toString().padStart(2, '0')
        return (if (negative) "-" else "") + "R$ $reais,$centavos"
    }

    /** Valor válido para um lançamento: positivo e até [MAX_CENTS]. */
    fun isValidAmount(cents: Long): Boolean = cents in 1..MAX_CENTS
}
