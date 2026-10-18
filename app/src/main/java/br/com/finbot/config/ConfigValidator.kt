package br.com.finbot.config

import java.time.DateTimeException
import java.time.ZoneId

/** Dados digitados em Ajustes/onboarding. A chave Gemini vazia significa "manter o valor salvo". */
data class ConfigForm(
    val displayName: String = "",
    val timezone: String = ConfigValidator.DEFAULT_TIMEZONE,
    val geminiKey: String = "",
    val geminiModel: String = ConfigValidator.DEFAULT_GEMINI_MODEL,
)

data class ConfigErrors(
    val displayName: String? = null,
    val timezone: String? = null,
    val geminiKey: String? = null,
    val geminiModel: String? = null,
) {
    val hasErrors: Boolean
        get() = listOf(displayName, timezone, geminiKey, geminiModel).any { it != null }
}

/** Validação pura (sem Android) dos campos de configuração. */
object ConfigValidator {
    const val DEFAULT_TIMEZONE = "America/Sao_Paulo"
    const val MAX_DISPLAY_NAME = 40

    /** Estável, mais barato e com nível gratuito (docs consultadas em 18/10/2026). Configurável em Ajustes. */
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"
    private val GEMINI_MODEL = Regex("^[a-z0-9][a-z0-9.\\-]{2,63}$")

    fun geminiModelError(raw: String): String? =
        if (GEMINI_MODEL.matches(raw.trim())) null else "Nome de modelo inválido (ex.: $DEFAULT_GEMINI_MODEL)."

    private val GEMINI_KEY_LENGTH = 20..200

    /** Nome é opcional; só usado para saudações locais. */
    fun displayNameError(raw: String): String? {
        val value = raw.trim()
        return when {
            value.length > MAX_DISPLAY_NAME -> "Use no máximo $MAX_DISPLAY_NAME caracteres."
            value.any { it.isISOControl() } -> "O nome contém caracteres inválidos."
            else -> null
        }
    }

    /** A chave Gemini é opcional: sem ela o app funciona só com comandos e formulários. */
    fun geminiKeyError(key: String): String? {
        val value = key.trim()
        return when {
            value.isEmpty() -> null
            value.any { it.isWhitespace() } -> "A chave não pode conter espaços."
            value.length !in GEMINI_KEY_LENGTH -> "Tamanho de chave inválido."
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

    fun validate(form: ConfigForm): ConfigErrors = ConfigErrors(
        displayName = displayNameError(form.displayName),
        timezone = timezoneError(form.timezone),
        geminiKey = geminiKeyError(form.geminiKey),
        geminiModel = geminiModelError(form.geminiModel),
    )
}
