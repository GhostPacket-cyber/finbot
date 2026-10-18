package br.com.finbot.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Falhas da API, já classificadas para mensagens ao usuário. Nunca carregam a chave nem o conteúdo enviado. */
sealed interface GeminiError {
    data object InvalidKey : GeminiError
    data object ModelNotFound : GeminiError
    data class RateLimited(val retryAfterSeconds: Long?) : GeminiError
    data class Server(val httpCode: Int) : GeminiError
    data object Timeout : GeminiError
    data object Network : GeminiError
    data class BadRequest(val httpCode: Int) : GeminiError
    data class Blocked(val reason: String) : GeminiError
    data class Truncated(val finishReason: String) : GeminiError
    data object EmptyResponse : GeminiError
}

sealed interface GeminiOutcome {
    /** Texto (JSON) produzido pelo modelo, ainda não validado. */
    data class Success(val text: String) : GeminiOutcome
    data class Failure(val error: GeminiError) : GeminiOutcome
}

data class AudioInput(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?) = other is AudioInput && other.mimeType == mimeType && other.bytes.contentEquals(bytes)
    override fun hashCode() = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

data class GeminiRequest(
    val model: String,
    val systemInstruction: String,
    val userText: String,
    val audio: AudioInput?,
    val schema: JSONObject,
)

interface GeminiApi {
    suspend fun generate(apiKey: String, request: GeminiRequest): GeminiOutcome
}

/**
 * Cliente REST mínimo para `models.generateContent` (v1beta) via HTTPS.
 * Sem SDK: HttpURLConnection + org.json. Cancelar a coroutine interrompe a conexão.
 */
class HttpGeminiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 45_000,
) : GeminiApi {

    override suspend fun generate(apiKey: String, request: GeminiRequest): GeminiOutcome = withContext(Dispatchers.IO) {
        require(MODEL_NAME.matches(request.model)) { "Nome de modelo inválido" }
        val connection = URL("$baseUrl/v1beta/models/${request.model}:generateContent").openConnection() as HttpURLConnection
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { connection.disconnect() }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.outputStream.use { it.write(buildBody(request).toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code in 200..299) parseSuccess(body) else GeminiOutcome.Failure(mapHttpError(code, body))
        } catch (_: SocketTimeoutException) {
            GeminiOutcome.Failure(GeminiError.Timeout)
        } catch (_: IOException) {
            GeminiOutcome.Failure(GeminiError.Network)
        } finally {
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        val MODEL_NAME = Regex("^[a-z0-9][a-z0-9.\\-]{2,63}$")

        /**
         * Corpo da requisição. `store=false` desativa o registro da chamada no servidor;
         * só a mensagem atual e os nomes das categorias são enviados (nenhum histórico financeiro).
         */
        fun buildBody(request: GeminiRequest): JSONObject {
            val parts = JSONArray().put(JSONObject().put("text", request.userText))
            request.audio?.let { audio ->
                parts.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", audio.mimeType)
                            .put("data", java.util.Base64.getEncoder().encodeToString(audio.bytes)),
                    ),
                )
            }
            return JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemInstruction))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("maxOutputTokens", 2048)
                        .put(
                            "responseFormat",
                            JSONObject().put("text", JSONObject().put("mimeType", "APPLICATION_JSON").put("schema", request.schema)),
                        ),
                )
                .put("store", false)
        }

        fun parseSuccess(body: String): GeminiOutcome = try {
            val json = JSONObject(body)
            val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            when {
                blockReason.isNotEmpty() -> GeminiOutcome.Failure(GeminiError.Blocked(blockReason))
                candidate == null -> GeminiOutcome.Failure(GeminiError.EmptyResponse)
                else -> {
                    val finish = candidate.optString("finishReason")
                    val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                    val text = (0 until parts.length())
                        .mapNotNull { parts.optJSONObject(it) }
                        .filter { !it.optBoolean("thought", false) } // ignora "pensamentos" do modelo
                        .joinToString("") { it.optString("text") }
                        .trim()
                    when {
                        finish == "MAX_TOKENS" -> GeminiOutcome.Failure(GeminiError.Truncated(finish))
                        finish in BLOCKING_FINISH -> GeminiOutcome.Failure(GeminiError.Blocked(finish))
                        text.isEmpty() -> GeminiOutcome.Failure(GeminiError.EmptyResponse)
                        else -> GeminiOutcome.Success(text)
                    }
                }
            }
        } catch (_: JSONException) {
            GeminiOutcome.Failure(GeminiError.EmptyResponse)
        }

        private val BLOCKING_FINISH = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")

        fun mapHttpError(code: Int, body: String): GeminiError {
            val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            val message = error?.optString("message").orEmpty()
            val details = error?.optJSONArray("details")
            val reasons = details?.let { d -> (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("reason") } }.orEmpty()
            return when {
                code == 400 && (reasons.any { it == "API_KEY_INVALID" } || message.contains("API key", ignoreCase = true)) -> GeminiError.InvalidKey
                code == 401 || code == 403 -> GeminiError.InvalidKey
                code == 404 -> GeminiError.ModelNotFound
                code == 429 -> GeminiError.RateLimited(retryDelaySeconds(details))
                code == 408 -> GeminiError.Timeout
                code >= 500 -> GeminiError.Server(code)
                else -> GeminiError.BadRequest(code)
            }
        }

        /** Lê `google.rpc.RetryInfo.retryDelay` (ex.: "31s"), quando presente. */
        private fun retryDelaySeconds(details: JSONArray?): Long? {
            details ?: return null
            for (i in 0 until details.length()) {
                val delay = details.optJSONObject(i)?.optString("retryDelay").orEmpty()
                Regex("^(\\d+)(?:\\.\\d+)?s$").find(delay)?.let { return it.groupValues[1].toLong() }
            }
            return null
        }
    }
}
