package br.com.finbot.security

import android.util.Log

/**
 * Remove padrões de segredos de qualquer texto destinado a logs.
 * Rede de segurança: o código não deve registrar conteúdo de mensagens, áudios, valores ou segredos.
 */
object LogSanitizer {
    private const val MAX_LENGTH = 300

    private val rules = listOf(
        // Chaves de API do Google.
        Regex("AIza[0-9A-Za-z_-]{35}") to "[CHAVE_REMOVIDA]",
        Regex("(?i)(x-goog-api-key\\s*[:=]\\s*)\\S+") to "$1[REMOVIDO]",
        Regex("(?i)([?&](?:key|token|api_key|access_token)=)[^&\\s]+") to "$1[REMOVIDO]",
        Regex("(?i)(bearer\\s+)\\S+") to "$1[REMOVIDO]",
    )

    fun sanitize(text: String): String {
        val redacted = rules.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
        return if (redacted.length > MAX_LENGTH) redacted.take(MAX_LENGTH) + "…" else redacted
    }
}

/** Logger do app. Registra eventos técnicos; para exceções, apenas o tipo (mensagens podem conter dados). */
object SafeLog {
    private const val TAG = "FinBot"

    fun i(event: String) {
        Log.i(TAG, LogSanitizer.sanitize(event))
    }

    fun w(event: String, error: Throwable? = null) {
        Log.w(TAG, LogSanitizer.sanitize(withType(event, error)))
    }

    fun e(event: String, error: Throwable? = null) {
        Log.e(TAG, LogSanitizer.sanitize(withType(event, error)))
    }

    private fun withType(event: String, error: Throwable?) =
        if (error == null) event else "$event (${error.javaClass.simpleName})"
}
