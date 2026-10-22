package br.com.finbot.importing

import br.com.finbot.assistant.CategorySuggester
import br.com.finbot.data.TransactionKind
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Chaves de deduplicação (gravadas em `transactions.dedup_key`, com índice único).
 * São hashes: não guardam o número da conta em claro.
 */
object DedupKeys {
    /** OFX: o banco garante FITID único por conta. */
    fun ofx(accountId: String?, fitId: String): String = "ofx:" + sha256("${accountId.orEmpty().trim()}|${fitId.trim()}")

    /**
     * CSV não tem identificador: data + valor (com sinal) + descrição normalizada + ordem da ocorrência no dia
     * (dois cafés iguais no mesmo dia viram #0 e #1, e reimportar o arquivo gera as mesmas chaves).
     */
    fun csv(date: LocalDate, kind: TransactionKind, amountCents: Long, description: String, occurrence: Int): String {
        val signed = if (kind == TransactionKind.EXPENSE) -amountCents else amountCents
        return "csv:" + sha256("$date|$signed|${CategorySuggester.normalize(description)}|$occurrence")
    }

    /** Atribui a ordem de ocorrência para itens idênticos no mesmo dia (na ordem do arquivo). */
    fun <T> withOccurrence(items: List<T>, identity: (T) -> String): List<Pair<T, Int>> {
        val seen = HashMap<String, Int>()
        return items.map { item ->
            val id = identity(item)
            val n = seen.getOrDefault(id, 0)
            seen[id] = n + 1
            item to n
        }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
