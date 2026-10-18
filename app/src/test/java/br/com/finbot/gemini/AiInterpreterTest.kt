package br.com.finbot.gemini

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AiInterpreterTest {

    private val today = LocalDate.of(2026, 10, 18)
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val categories = AllowedCategories(listOf("Mercado", "Outros"), listOf("Salário", "Outros"))
    private val validJson =
        """{"intent":"CREATE_TRANSACTION","confidence":0.9,"transcript":null,"items":[{"kind":"EXPENSE","amount_cents":4590,"currency":"BRL","description":"mercado","category":"Mercado","date":"2026-10-18"}],"report":null,"requires_clarification":false,"question":null}"""

    private class FakeApi(vararg responses: GeminiOutcome) : GeminiApi {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<GeminiRequest>()
        override suspend fun generate(apiKey: String, request: GeminiRequest): GeminiOutcome {
            requests += request
            return queue.removeFirst()
        }
    }

    private fun interpreter(api: GeminiApi, availability: AiAvailability = AiAvailability.Ready("k".repeat(30), "gemini-3.5-flash-lite")) =
        AiInterpreter(api, { availability }, backoff = {})

    @Test
    fun `sem consentimento ou sem chave nao chama a api`() = runTest {
        val api = FakeApi()
        val noConsent = interpreter(api, AiAvailability.NoConsent).interpretText("x", today, zone, categories)
        val noKey = interpreter(api, AiAvailability.NoKey).interpretText("x", today, zone, categories)
        assertEquals(AiOutcome.Failed(AiInterpreter.Messages.NO_CONSENT), noConsent)
        assertEquals(AiOutcome.Failed(AiInterpreter.Messages.NO_KEY), noKey)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `resposta valida vira interpretacao`() = runTest {
        val outcome = interpreter(FakeApi(GeminiOutcome.Success(validJson))).interpretText("gastei 45,90 no mercado", today, zone, categories)
        assertTrue((outcome as AiOutcome.Interpreted).interpretation is AiInterpretation.Transactions)
    }

    @Test
    fun `envia apenas a mensagem atual marcada como dados`() = runTest {
        val api = FakeApi(GeminiOutcome.Success(validJson))
        interpreter(api).interpretText("gastei 45,90 no mercado", today, zone, categories)
        val sent = api.requests.single()
        assertTrue(sent.userText.contains("<<<\ngastei 45,90 no mercado\n>>>"))
        assertTrue(sent.systemInstruction.contains("Hoje é 2026-10-18"))
        assertTrue(sent.systemInstruction.contains("America/Sao_Paulo"))
        assertTrue(sent.systemInstruction.contains("Mercado"))
    }

    @Test
    fun `falha transitoria tenta de novo uma vez`() = runTest {
        val api = FakeApi(GeminiOutcome.Failure(GeminiError.Server(503)), GeminiOutcome.Success(validJson))
        val outcome = interpreter(api).interpretText("x", today, zone, categories)
        assertTrue(outcome is AiOutcome.Interpreted)
        assertEquals(2, api.requests.size)
    }

    @Test
    fun `429 nao e repetido e informa espera`() = runTest {
        val api = FakeApi(GeminiOutcome.Failure(GeminiError.RateLimited(31)))
        val outcome = interpreter(api).interpretText("x", today, zone, categories) as AiOutcome.Failed
        assertEquals(1, api.requests.size)
        assertTrue(outcome.message.contains("31 s"))
        assertTrue(outcome.message.contains(AiInterpreter.Messages.FALLBACK))
    }

    @Test
    fun `json invalido do modelo nao vira lancamento`() = runTest {
        val outcome = interpreter(FakeApi(GeminiOutcome.Success("""{"intent":"CREATE_TRANSACTION"}"""))).interpretText("x", today, zone, categories)
        assertEquals(AiOutcome.Failed(AiInterpreter.Messages.INVALID_RESPONSE), outcome)
    }

    @Test
    fun `audio vazio ou truncado pede para repetir`() = runTest {
        val outcome = interpreter(FakeApi(GeminiOutcome.Failure(GeminiError.EmptyResponse)))
            .interpretAudio(AudioInput(byteArrayOf(1), "audio/aac"), today, zone, categories) as AiOutcome.Failed
        assertTrue(outcome.message.contains("áudio"))
    }

    @Test
    fun `todas as mensagens de erro oferecem o caminho sem ia`() {
        listOf(
            GeminiError.InvalidKey, GeminiError.ModelNotFound, GeminiError.RateLimited(null), GeminiError.Server(500),
            GeminiError.Timeout, GeminiError.Network, GeminiError.Blocked("SAFETY"), GeminiError.Truncated("MAX_TOKENS"),
            GeminiError.EmptyResponse, GeminiError.BadRequest(400),
        ).forEach { error ->
            assertTrue(error.toString(), AiInterpreter.Messages.forError(error, isAudio = false).contains(AiInterpreter.Messages.FALLBACK))
        }
    }
}
