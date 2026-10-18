package br.com.finbot.gemini

import br.com.finbot.security.SafeLog
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** Pré-requisitos para chamar a IA, lidos no momento da chamada. */
sealed interface AiAvailability {
    data object NoConsent : AiAvailability
    data object NoKey : AiAvailability
    data class Ready(val apiKey: String, val model: String) : AiAvailability
}

sealed interface AiOutcome {
    data class Interpreted(val interpretation: AiInterpretation) : AiOutcome

    /** IA indisponível ou com erro; [message] é para o usuário e sempre aponta o caminho sem IA. */
    data class Failed(val message: String) : AiOutcome
}

/**
 * Chama o Gemini com o mínimo de dados (mensagem atual e nomes de categorias), valida a resposta
 * e nunca grava nada: o resultado vira cartão de confirmação no chat.
 */
class AiInterpreter(
    private val api: GeminiApi,
    private val availability: suspend () -> AiAvailability,
    private val backoff: suspend (attempt: Int) -> Unit = { attempt -> delay(1_000L * attempt + Random.nextLong(0, 500)) },
) {

    suspend fun isReady(): Boolean = availability() is AiAvailability.Ready

    /** Texto que diz exatamente o que falta para usar a IA (null se já está pronta). */
    suspend fun missingSetupHint(): String? = when (availability()) {
        is AiAvailability.Ready -> null
        AiAvailability.NoConsent -> Messages.HINT_NO_CONSENT
        AiAvailability.NoKey -> Messages.HINT_NO_KEY
    }

    suspend fun interpretText(text: String, today: LocalDate, zone: ZoneId, categories: AllowedCategories): AiOutcome =
        interpret(GeminiPrompt.userText(text), null, today, zone, categories)

    suspend fun interpretAudio(audio: AudioInput, today: LocalDate, zone: ZoneId, categories: AllowedCategories): AiOutcome =
        interpret(GeminiPrompt.AUDIO_INSTRUCTION, audio, today, zone, categories)

    private suspend fun interpret(
        userText: String,
        audio: AudioInput?,
        today: LocalDate,
        zone: ZoneId,
        categories: AllowedCategories,
    ): AiOutcome {
        val ready = when (val a = availability()) {
            AiAvailability.NoConsent -> return AiOutcome.Failed(Messages.NO_CONSENT)
            AiAvailability.NoKey -> return AiOutcome.Failed(Messages.NO_KEY)
            is AiAvailability.Ready -> a
        }
        val request = GeminiRequest(
            model = ready.model,
            systemInstruction = GeminiPrompt.systemInstruction(today, zone, categories),
            userText = userText,
            audio = audio,
            schema = GeminiPrompt.schema(categories),
        )

        var outcome = api.generate(ready.apiKey, request)
        // Uma nova tentativa só para falhas transitórias; 429 não é repetido automaticamente.
        if (outcome is GeminiOutcome.Failure && outcome.error.isTransient()) {
            backoff(1)
            outcome = api.generate(ready.apiKey, request)
        }

        return when (outcome) {
            is GeminiOutcome.Failure -> {
                SafeLog.w("Gemini falhou: ${outcome.error.logName()}")
                AiOutcome.Failed(Messages.forError(outcome.error, isAudio = audio != null))
            }
            is GeminiOutcome.Success -> when (val v = GeminiResponseValidator.validate(outcome.text, today, categories)) {
                is ValidationOutcome.Valid -> AiOutcome.Interpreted(v.interpretation)
                is ValidationOutcome.Invalid -> {
                    SafeLog.w("Resposta do Gemini rejeitada: ${v.reason}")
                    AiOutcome.Failed(Messages.INVALID_RESPONSE)
                }
            }
        }
    }

    private fun GeminiError.isTransient() =
        this is GeminiError.Server || this is GeminiError.Timeout || this is GeminiError.Network

    private fun GeminiError.logName(): String = when (this) {
        is GeminiError.Server -> "HTTP $httpCode"
        is GeminiError.BadRequest -> "HTTP $httpCode"
        is GeminiError.RateLimited -> "HTTP 429"
        is GeminiError.Blocked -> "bloqueado ($reason)"
        is GeminiError.Truncated -> "truncado"
        else -> this::class.simpleName.orEmpty()
    }

    object Messages {
        const val FALLBACK = "Você pode registrar sem IA: gastei 45,90 no mercado ou /gasto 45 almoço."
        const val HINT_NO_CONSENT =
            "Para frases livres, vários gastos numa mensagem e áudio, ligue \"Envio ao Gemini\" em Ajustes (a chave sozinha não basta)."
        const val HINT_NO_KEY = "Para frases livres, vários gastos numa mensagem e áudio, salve a chave da Gemini API em Ajustes."
        const val NO_CONSENT = "A interpretação por IA está desativada. Ative em Ajustes para frases livres e áudio. $FALLBACK"
        const val NO_KEY = "Falta a chave da Gemini API em Ajustes. $FALLBACK"
        const val INVALID_RESPONSE = "Não consegui interpretar com segurança. Nada foi registrado. $FALLBACK"

        fun forError(error: GeminiError, isAudio: Boolean): String = when (error) {
            GeminiError.InvalidKey -> "A chave da Gemini API foi recusada. Confira ou substitua em Ajustes. $FALLBACK"
            GeminiError.ModelNotFound -> "O modelo configurado não existe ou não está disponível para sua chave. Confira em Ajustes. $FALLBACK"
            is GeminiError.RateLimited -> {
                val wait = error.retryAfterSeconds?.let { "Tente de novo em cerca de $it s." } ?: "Tente de novo mais tarde."
                "Limite de uso da Gemini API atingido (o plano gratuito tem cota). $wait $FALLBACK"
            }
            is GeminiError.Server -> "O serviço do Gemini está instável agora. $FALLBACK"
            GeminiError.Timeout -> "O Gemini demorou demais para responder. $FALLBACK"
            GeminiError.Network -> "Sem conexão com a internet. $FALLBACK"
            is GeminiError.Blocked -> "O Gemini recusou processar esta mensagem. $FALLBACK"
            is GeminiError.Truncated, GeminiError.EmptyResponse ->
                if (isAudio) "Não consegui entender o áudio. Tente falar mais perto do microfone ou digite. $FALLBACK"
                else INVALID_RESPONSE
            is GeminiError.BadRequest -> "A requisição ao Gemini foi recusada (HTTP ${error.httpCode}). $FALLBACK"
        }
    }
}
