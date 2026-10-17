package br.com.finbot.config

import java.time.DateTimeException
import java.time.ZoneId

/** Dados digitados na tela administrativa. Campos de segredo vazios significam "manter o valor salvo". */
data class ConfigForm(
    val telegramToken: String = "",
    val geminiKey: String = "",
    val user1Id: String = "",
    val user2Id: String = "",
    val timezone: String = ConfigValidator.DEFAULT_TIMEZONE,
)

data class ConfigErrors(
    val telegramToken: String? = null,
    val geminiKey: String? = null,
    val user1Id: String? = null,
    val user2Id: String? = null,
    val timezone: String? = null,
) {
    val hasErrors: Boolean
        get() = listOf(telegramToken, geminiKey, user1Id, user2Id, timezone).any { it != null }
}

/** Validação pura (sem Android) dos campos administrativos. */
object ConfigValidator {
    const val DEFAULT_TIMEZONE = "America/Sao_Paulo"
    const val MAX_AUTHORIZED_USERS = 2

    // Formato emitido pelo @BotFather: <id numérico do bot>:<segredo base64url>.
    private val TELEGRAM_TOKEN = Regex("^\\d{5,20}:[A-Za-z0-9_-]{30,64}$")
    private val USER_ID = Regex("^\\d{1,15}$")
    private val GEMINI_KEY_LENGTH = 20..200

    fun telegramTokenError(token: String, hasStoredToken: Boolean): String? {
        val value = token.trim()
        return when {
            value.isEmpty() && hasStoredToken -> null
            value.isEmpty() -> "Informe o token do bot (fornecido pelo @BotFather)."
            !TELEGRAM_TOKEN.matches(value) -> "Formato inválido. Esperado algo como 123456789:AAF…"
            else -> null
        }
    }

    /** A chave Gemini é opcional: sem ela o bot funciona só com comandos estruturados. */
    fun geminiKeyError(key: String): String? {
        val value = key.trim()
        return when {
            value.isEmpty() -> null
            value.any { it.isWhitespace() } -> "A chave não pode conter espaços."
            value.length !in GEMINI_KEY_LENGTH -> "Tamanho de chave inválido."
            else -> null
        }
    }

    fun parseUserId(raw: String): Long? {
        val value = raw.trim()
        if (!USER_ID.matches(value)) return null
        return value.toLong().takeIf { it > 0 }
    }

    fun userIdError(raw: String, required: Boolean): String? {
        val value = raw.trim()
        return when {
            value.isEmpty() && required -> "Informe ao menos um ID de usuário do Telegram."
            value.isEmpty() -> null
            parseUserId(value) == null -> "O ID deve ser um número inteiro positivo (até 15 dígitos)."
            else -> null
        }
    }

    fun timezoneError(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return "Informe o fuso horário (ex.: $DEFAULT_TIMEZONE)."
        // Somente IDs de região (Área/Cidade); offsets fixos não acompanham mudanças de horário.
        if (!value.contains('/')) return "Use um fuso no formato Área/Cidade, ex.: $DEFAULT_TIMEZONE."
        return try {
            ZoneId.of(value)
            null
        } catch (_: DateTimeException) {
            "Fuso horário desconhecido."
        }
    }

    fun validate(form: ConfigForm, hasStoredToken: Boolean): ConfigErrors {
        val user1Error = userIdError(form.user1Id, required = true)
        var user2Error = userIdError(form.user2Id, required = false)
        if (user1Error == null && user2Error == null) {
            val first = parseUserId(form.user1Id)
            val second = parseUserId(form.user2Id)
            if (second != null && first == second) user2Error = "Os dois IDs devem ser diferentes."
        }
        return ConfigErrors(
            telegramToken = telegramTokenError(form.telegramToken, hasStoredToken),
            geminiKey = geminiKeyError(form.geminiKey),
            user1Id = user1Error,
            user2Id = user2Error,
            timezone = timezoneError(form.timezone),
        )
    }

    /** IDs autorizados na ordem do formulário, sem vazios. Chamar somente após [validate] sem erros. */
    fun authorizedIds(form: ConfigForm): List<Long> =
        listOfNotNull(parseUserId(form.user1Id), parseUserId(form.user2Id)).take(MAX_AUTHORIZED_USERS)
}
