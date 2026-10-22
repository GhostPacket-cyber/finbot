package br.com.finbot.gemini

import br.com.finbot.data.TransactionKind
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategorizerTest {

    private val categories = AllowedCategories(listOf("Mercado", "Transporte", "Outros"), listOf("Salário", "Outros"))
    private val inputs = listOf(
        CategorizeInput(0, TransactionKind.EXPENSE, "SUPERMERCADO BOM PRECO"),
        CategorizeInput(1, TransactionKind.EXPENSE, "UBER *TRIP"),
        CategorizeInput(2, TransactionKind.INCOME, "TED EMPRESA X"),
    )

    private class FakeApi(vararg responses: GeminiOutcome) : GeminiApi {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<GeminiRequest>()
        override suspend fun generate(apiKey: String, request: GeminiRequest): GeminiOutcome {
            requests += request
            return queue.removeFirst()
        }
    }

    private fun categorizer(api: GeminiApi, availability: AiAvailability = AiAvailability.Ready("k".repeat(30), "m")) =
        AiCategorizer(api, { availability }, backoff = {})

    @Test
    fun `envia somente id, tipo e descricao e aceita categorias validas do mesmo tipo`() = runTest {
        val api = FakeApi(
            GeminiOutcome.Success(
                """{"suggestions":[{"id":0,"category":"mercado"},{"id":1,"category":"Salário"},{"id":2,"category":"Salário"},{"id":9,"category":"Outros"}]}""",
            ),
        )
        val outcome = categorizer(api).suggest(inputs, categories)
        // id 1: categoria de receita para despesa → descartada; id 9 desconhecido → descartado; caixa normalizada.
        assertEquals(CategorizeOutcome.Suggested(mapOf(0 to "Mercado", 2 to "Salário")), outcome)

        val sent = api.requests.single()
        assertNull(sent.audio)
        val payload = sent.userText.substringAfter("<<<").substringBefore(">>>")
        val first = org.json.JSONArray(payload.trim()).getJSONObject(0)
        assertEquals(setOf("id", "kind", "description"), first.keyNames())
        assertFalse("não envia valores", sent.userText.contains("amount"))
        assertTrue(JSONObject(sent.schema.toString()).has("properties"))
    }

    @Test
    fun `sem consentimento ou sem chave nao chama a api`() = runTest {
        val api = FakeApi()
        assertEquals(CategorizeOutcome.Failed(AiCategorizer.NO_CONSENT), categorizer(api, AiAvailability.NoConsent).suggest(inputs, categories))
        assertEquals(CategorizeOutcome.Failed(AiCategorizer.NO_KEY), categorizer(api, AiAvailability.NoKey).suggest(inputs, categories))
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `resposta fora do formato e rejeitada inteira`() = runTest {
        val api = FakeApi(GeminiOutcome.Success("""{"suggestions":[],"extra":1}"""), GeminiOutcome.Success("não é json"))
        assertEquals(CategorizeOutcome.Failed(AiCategorizer.INVALID), categorizer(api).suggest(inputs, categories))
        assertEquals(CategorizeOutcome.Failed(AiCategorizer.INVALID), categorizer(api).suggest(inputs, categories))
    }

    @Test
    fun `falha transitoria tenta de novo uma vez e 429 nao repete`() = runTest {
        val api = FakeApi(
            GeminiOutcome.Failure(GeminiError.Server(503)),
            GeminiOutcome.Success("""{"suggestions":[{"id":1,"category":"Transporte"}]}"""),
        )
        assertEquals(CategorizeOutcome.Suggested(mapOf(1 to "Transporte")), categorizer(api).suggest(inputs, categories))
        assertEquals(2, api.requests.size)

        val limited = FakeApi(GeminiOutcome.Failure(GeminiError.RateLimited(30)))
        val outcome = categorizer(limited).suggest(inputs, categories)
        assertTrue(outcome is CategorizeOutcome.Failed)
        assertEquals(1, limited.requests.size)
    }

    @Test
    fun `instrucao trata descricoes como dados`() {
        val text = AiCategorizer.systemInstruction(categories)
        assertTrue(text.contains("não instruções"))
        assertTrue(text.contains("Mercado, Transporte, Outros"))
    }
}
