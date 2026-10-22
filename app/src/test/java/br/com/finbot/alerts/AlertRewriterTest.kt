package br.com.finbot.alerts

import br.com.finbot.gemini.AiAvailability
import br.com.finbot.gemini.GeminiApi
import br.com.finbot.gemini.GeminiError
import br.com.finbot.gemini.GeminiOutcome
import br.com.finbot.gemini.GeminiRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRewriterTest {
    private val original = "Você já usou 84% do orçamento de Mercado em outubro/2026: R$ 420,00 de R$ 500,00. Restam R$ 80,00."

    private class FakeApi(private val outcome: GeminiOutcome) : GeminiApi {
        val requests = mutableListOf<GeminiRequest>()
        override suspend fun generate(apiKey: String, request: GeminiRequest): GeminiOutcome {
            requests += request
            return outcome
        }
    }

    private fun json(text: String) = GeminiOutcome.Success("""{"text":${org.json.JSONObject.quote(text)}}""")

    @Test
    fun `aceita reescrita com exatamente os mesmos numeros`() = runTest {
        val ok = "Atenção: 84% do orçamento de Mercado de outubro/2026 já foi usado (R$ 420,00 de R$ 500,00). Ainda dá para gastar R$ 80,00."
        val api = FakeApi(json(ok))
        assertEquals(ok, AlertRewriter(api) { AiAvailability.Ready("k".repeat(30), "m") }.rewrite(original))
        assertTrue(api.requests.single().userText.contains(original))
        assertTrue(api.requests.single().systemInstruction.contains("Não calcule"))
    }

    @Test
    fun `rejeita numero alterado, removido ou acrescentado`() {
        assertNull(AlertRewriter.validate(original, """{"text":"Você usou 85% do orçamento de Mercado em outubro/2026: R$ 420,00 de R$ 500,00. Restam R$ 80,00."}"""))
        assertNull(AlertRewriter.validate(original, """{"text":"Você usou 84% do orçamento de Mercado: R$ 420,00 de R$ 500,00."}"""))
        assertNull(AlertRewriter.validate(original, """{"text":"Em 3 dias: 84% do orçamento de Mercado em outubro/2026, R$ 420,00 de R$ 500,00. Restam R$ 80,00."}"""))
        assertNull(AlertRewriter.validate(original, """{"text":"x","extra":1}"""))
        assertNull(AlertRewriter.validate(original, "não é json"))
        assertNull(AlertRewriter.validate(original, """{"text":""}"""))
    }

    @Test
    fun `sem consentimento ou com erro fica o texto original`() = runTest {
        val api = FakeApi(json(original))
        assertNull(AlertRewriter(api) { AiAvailability.NoConsent }.rewrite(original))
        assertNull(AlertRewriter(api) { AiAvailability.NoKey }.rewrite(original))
        assertTrue(api.requests.isEmpty())
        assertNull(AlertRewriter(FakeApi(GeminiOutcome.Failure(GeminiError.Network))) { AiAvailability.Ready("k".repeat(30), "m") }.rewrite(original))
    }
}
