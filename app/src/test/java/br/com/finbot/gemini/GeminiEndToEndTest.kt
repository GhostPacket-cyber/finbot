package br.com.finbot.gemini

import br.com.finbot.data.TransactionKind
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * ETAPA 12 — HTTP mock de ponta a ponta: servidor falso → [HttpGeminiClient] → [AiInterpreter] (validação e nova tentativa).
 * Cobre respostas válidas, inválidas, com campos extras, 429, 500 e timeout.
 */
class GeminiEndToEndTest {
    private lateinit var server: MockWebServer
    private val today = LocalDate.of(2026, 10, 18)
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val categories = AllowedCategories(listOf("Mercado", "Outros"), listOf("Salário", "Outros"))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun interpreter(readTimeoutMs: Int = 3_000) = AiInterpreter(
        HttpGeminiClient(baseUrl = server.url("/").toString().trimEnd('/'), connectTimeoutMs = 2_000, readTimeoutMs = readTimeoutMs),
        availability = { AiAvailability.Ready("chave-sintetica-de-teste-0123456789", "gemini-3.5-flash-lite") },
        backoff = {},
    )

    private fun body(text: String) = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject().put("finishReason", "STOP")
                .put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text)))),
        ),
    ).toString()

    private fun enqueue(code: Int, text: String) = server.enqueue(MockResponse.Builder().code(code).body(text).build())

    private val valid = """{"intent":"CREATE_TRANSACTION","confidence":0.9,"transcript":null,"items":[{"kind":"EXPENSE","amount_cents":4590,"currency":"BRL","description":"mercado","category":"Mercado","date":"2026-10-18"}],"report":null,"requires_clarification":false,"question":null}"""

    private suspend fun ask() = interpreter().interpretText("gastei 45,90 no mercado", today, zone, categories)

    @Test
    fun `resposta valida vira interpretacao`() = runTest {
        enqueue(200, body(valid))
        val outcome = ask()
        assertTrue(outcome.toString(), outcome is AiOutcome.Interpreted)
        val item = ((outcome as AiOutcome.Interpreted).interpretation as AiInterpretation.Transactions).items.single()
        assertEquals(TransactionKind.EXPENSE, item.kind)
        assertEquals(4_590L, item.amountCents)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `resposta invalida ou com campos extras e rejeitada sem nova tentativa`() = runTest {
        enqueue(200, body("isto não é json"))
        assertEquals(AiOutcome.Failed(AiInterpreter.Messages.INVALID_RESPONSE), ask())
        enqueue(200, body(valid.replace("\"question\":null}", "\"question\":null,\"grava_direto\":true}")))
        assertEquals(AiOutcome.Failed(AiInterpreter.Messages.INVALID_RESPONSE), ask())
        enqueue(200, body(valid.replace("\"date\":\"2026-10-18\"", "\"date\":\"2026-10-18\",\"account\":\"x\"")))
        assertEquals(AiOutcome.Failed(AiInterpreter.Messages.INVALID_RESPONSE), ask())
        enqueue(200, body(valid.replace("4590", "-4590")))
        assertEquals(AiOutcome.Failed(AiInterpreter.Messages.INVALID_RESPONSE), ask())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `429 nao repete e informa a cota`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(429).body("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"17s"}]}}""").build(),
        )
        val outcome = ask()
        assertTrue(outcome.toString(), outcome is AiOutcome.Failed && outcome.message.contains("17 s"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `500 repete uma vez e recupera`() = runTest {
        enqueue(500, """{"error":{"code":500}}""")
        enqueue(200, body(valid))
        assertTrue(ask() is AiOutcome.Interpreted)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `500 duas vezes falha com caminho sem IA`() = runTest {
        enqueue(500, """{"error":{"code":500}}""")
        enqueue(503, """{"error":{"code":503}}""")
        val outcome = ask()
        assertTrue(outcome is AiOutcome.Failed && outcome.message.contains(AiInterpreter.Messages.FALLBACK))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `timeout repete uma vez e depois falha`() = runTest {
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body(body(valid)).headersDelay(2, TimeUnit.SECONDS).build()) }
        val outcome = interpreter(readTimeoutMs = 300).interpretText("gastei 45,90 no mercado", today, zone, categories)
        assertTrue(outcome is AiOutcome.Failed && outcome.message.contains("demorou"))
        assertEquals(2, server.requestCount)
    }
}
