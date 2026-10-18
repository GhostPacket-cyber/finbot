package br.com.finbot.gemini

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class GeminiClientTest {

    private lateinit var server: MockWebServer
    private val key = "chave-sintetica-de-teste-0123456789"
    private val categories = AllowedCategories(listOf("Mercado", "Outros"), listOf("Salário", "Outros"))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun client(readTimeoutMs: Int = 5_000) =
        HttpGeminiClient(baseUrl = server.url("/").toString().trimEnd('/'), connectTimeoutMs = 2_000, readTimeoutMs = readTimeoutMs)

    private fun request(audio: AudioInput? = null) = GeminiRequest(
        model = "gemini-3.5-flash-lite",
        systemInstruction = GeminiPrompt.systemInstruction(LocalDate.of(2026, 10, 18), ZoneId.of("America/Sao_Paulo"), categories),
        userText = GeminiPrompt.userText("gastei 45,90 no mercado"),
        audio = audio,
        schema = GeminiPrompt.schema(categories),
    )

    private fun enqueue(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun success(text: String) =
        JSONObject().put(
            "candidates",
            org.json.JSONArray().put(
                JSONObject()
                    .put("finishReason", "STOP")
                    .put("content", JSONObject().put("role", "model").put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))),
            ),
        ).toString()

    @Test
    fun `requisicao usa endpoint chave em cabecalho store false e formato json`() = runTest {
        enqueue(200, success("{}"))
        client().generate(key, request())

        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", recorded.url.encodedPath)
        assertEquals(key, recorded.headers["x-goog-api-key"])
        assertFalse("a chave não vai na URL", recorded.url.toString().contains(key))
        val body = JSONObject(recorded.body!!.utf8())
        assertEquals(false, body.getBoolean("store"))
        val format = body.getJSONObject("generationConfig").getJSONObject("responseFormat").getJSONObject("text")
        assertEquals("APPLICATION_JSON", format.getString("mimeType"))
        assertEquals("object", format.getJSONObject("schema").getString("type"))
        // Só a mensagem atual: nenhum histórico.
        assertEquals(1, body.getJSONArray("contents").length())
    }

    @Test
    fun `audio vai inline em base64 com mime`() = runTest {
        enqueue(200, success("{}"))
        client().generate(key, request(AudioInput(byteArrayOf(1, 2, 3), "audio/aac")))
        val parts = JSONObject(server.takeRequest().body!!.utf8()).getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val inline = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("audio/aac", inline.getString("mimeType"))
        assertEquals("AQID", inline.getString("data"))
    }

    @Test
    fun `sucesso devolve texto e ignora pensamentos`() = runTest {
        val body = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"pensando...","thought":true},{"text":"{\"a\":1}"}]}}]}"""
        enqueue(200, body)
        assertEquals(GeminiOutcome.Success("{\"a\":1}"), client().generate(key, request()))
    }

    @Test
    fun `erros http sao classificados`() = runTest {
        val cases = listOf(
            400 to """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""" to GeminiError.InvalidKey,
            403 to """{"error":{"code":403,"status":"PERMISSION_DENIED"}}""" to GeminiError.InvalidKey,
            404 to """{"error":{"code":404,"status":"NOT_FOUND"}}""" to GeminiError.ModelNotFound,
            429 to """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"31s"}]}}""" to GeminiError.RateLimited(31),
            429 to """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""" to GeminiError.RateLimited(null),
            500 to """{"error":{"code":500,"status":"INTERNAL"}}""" to GeminiError.Server(500),
            503 to "Service Unavailable" to GeminiError.Server(503),
            400 to """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"bad schema"}}""" to GeminiError.BadRequest(400),
        )
        cases.forEach { (pair, expected) ->
            enqueue(pair.first, pair.second)
            assertEquals("HTTP ${pair.first}", GeminiOutcome.Failure(expected), client().generate(key, request()))
        }
    }

    @Test
    fun `bloqueio truncamento e resposta vazia`() = runTest {
        enqueue(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertEquals(GeminiOutcome.Failure(GeminiError.Blocked("SAFETY")), client().generate(key, request()))
        enqueue(200, """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{\"intent\":"}]}}]}""")
        assertEquals(GeminiOutcome.Failure(GeminiError.Truncated("MAX_TOKENS")), client().generate(key, request()))
        enqueue(200, """{"candidates":[]}""")
        assertEquals(GeminiOutcome.Failure(GeminiError.EmptyResponse), client().generate(key, request()))
        enqueue(200, "<html>proxy</html>")
        assertEquals(GeminiOutcome.Failure(GeminiError.EmptyResponse), client().generate(key, request()))
    }

    @Test
    fun `timeout de leitura`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(success("{}")).headersDelay(2, TimeUnit.SECONDS).build())
        assertEquals(GeminiOutcome.Failure(GeminiError.Timeout), client(readTimeoutMs = 300).generate(key, request()))
    }

    @Test
    fun `servidor fora do ar vira erro de rede`() = runTest {
        val url = server.url("/").toString().trimEnd('/')
        server.close()
        assertEquals(GeminiOutcome.Failure(GeminiError.Network), HttpGeminiClient(baseUrl = url, connectTimeoutMs = 500).generate(key, request()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nome de modelo com caracteres de caminho e rejeitado`() = runTest {
        client().generate(key, request().copy(model = "../../v1/files"))
    }
}
