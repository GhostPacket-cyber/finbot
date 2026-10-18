package br.com.finbot.assistant

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.data.ChatRole
import br.com.finbot.data.TestFinance
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.gemini.AiAvailability
import br.com.finbot.gemini.AiInterpreter
import br.com.finbot.gemini.GeminiError
import br.com.finbot.gemini.GeminiOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatAssistantAiTest {

    private lateinit var f: TestFinance

    @Before
    fun setUp() = runTest {
        f = TestFinance() // 17/10/2026, 12:00 em São Paulo
        f.seed()
        f.aiAvailability = AiAvailability.Ready("chave-sintetica-de-teste-0123456789", "gemini-3.5-flash-lite")
    }

    @After
    fun tearDown() = f.close()

    private suspend fun items() = f.assistant.observe().first()
    private suspend fun lastReply() = items().first { it.role == ChatRole.ASSISTANT }
    private suspend fun lastCard() = requireNotNull(items().firstNotNullOfOrNull { it.card })

    private fun item(kind: String, cents: Long, description: String, category: String?, date: String = "2026-10-17") =
        """{"kind":"$kind","amount_cents":$cents,"currency":"BRL","description":"$description","category":${category?.let { "\"$it\"" } ?: "null"},"date":"$date"}"""

    private fun reply(intent: String, items: List<String> = emptyList(), report: String? = null, clarify: Boolean = false, question: String? = null, transcript: String? = null) =
        GeminiOutcome.Success(
            """{"intent":"$intent","confidence":0.9,"transcript":${transcript?.let { "\"$it\"" } ?: "null"},"items":[${items.joinToString(",")}],""" +
                """"report":${report?.let { "\"$it\"" } ?: "null"},"requires_clarification":$clarify,"question":${question?.let { "\"$it\"" } ?: "null"}}""",
        )

    @Test
    fun frasesQueOParserEntendeNaoVaoAoGemini() = runTest {
        f.assistant.send("gastei 10 no café")
        f.assistant.send("/saldo")
        assertTrue("minimização: nada enviado ao Gemini", f.gemini.requests.isEmpty())
    }

    @Test
    fun semConsentimentoNadaEEnviado() = runTest {
        f.aiAvailability = AiAvailability.NoConsent
        f.assistant.send("comprei um presente de quarenta reais")
        assertTrue(f.gemini.requests.isEmpty())
        assertTrue(lastReply().text.contains(AiInterpreter.Messages.HINT_NO_CONSENT))
    }

    @Test
    fun variosGastosViramLoteComSomaEmKotlinEConfirmacaoAtomica() = runTest {
        f.gemini.responses += reply(
            "CREATE_TRANSACTION",
            listOf(item("EXPENSE", 1_000, "café", "Alimentação"), item("EXPENSE", 2_350, "almoço", "Alimentação"), item("INCOME", 5_000, "pix do João", null)),
        )
        f.assistant.send("gastei 10 no café e 23,50 no almoço e recebi 50 de pix do João")

        val card = lastCard()
        val batch = card.payload as ActionPayload.CreateBatch
        assertEquals(3, batch.items.size)
        assertEquals(3_350L, batch.expenseCents)
        assertEquals(5_000L, batch.incomeCents)
        assertEquals("nada antes de confirmar", 0L, f.summaries.balanceCents())

        f.assistant.confirm(card.actionId)
        assertEquals(5_000L - 3_350L, f.summaries.balanceCents())
        val rows = f.db.transactionDao().listRecentConfirmed(10, 0)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.transaction.source == TransactionSource.AI })

        f.assistant.confirm(card.actionId)
        assertEquals("segunda confirmação não grava", 3, f.db.transactionDao().listRecentConfirmed(10, 0).size)
    }

    @Test
    fun loteFalhaInteiroSeUmItemFicarInvalido() = runTest {
        f.gemini.responses += reply(
            "CREATE_TRANSACTION",
            listOf(item("EXPENSE", 1_000, "café", "Alimentação"), item("EXPENSE", 2_000, "cinema", "Lazer")),
        )
        f.assistant.send("10 de café e 20 de cinema")
        val card = lastCard()
        // Categoria arquivada entre o cartão e a confirmação: o 2º item falha, o 1º não pode ficar gravado.
        f.categories.archive(f.categoryId("Lazer", TransactionKind.EXPENSE))
        f.assistant.confirm(card.actionId)

        assertEquals(0, f.db.transactionDao().listRecentConfirmed(10, 0).size)
        assertEquals(CardStatus.PENDING, lastCard().status)
        assertTrue(lastReply().text.startsWith("Não foi possível registrar"))
    }

    @Test
    fun consultaViaIaUsaNumerosDoBanco() = runTest {
        f.assistant.send("recebi 2.500 de salário")
        f.assistant.confirm(lastCard().actionId)
        f.gemini.responses += reply("GET_REPORT", report = "BALANCE")
        f.assistant.send("me diz quanto sobrou pra mim")
        assertTrue(lastReply().text.contains("R$ 2.500,00"))
    }

    @Test
    fun esclarecimentoNaoCriaCartao() = runTest {
        f.gemini.responses += reply("CREATE_TRANSACTION", clarify = true, question = "Quanto foi o almoço?")
        f.assistant.send("almocei fora hoje")
        assertEquals("Quanto foi o almoço?", lastReply().text)
        assertNull(items().firstNotNullOfOrNull { it.card })
    }

    @Test
    fun respostaComCamposExtrasERejeitada() = runTest {
        f.gemini.responses += GeminiOutcome.Success(
            """{"intent":"CREATE_TRANSACTION","confidence":0.9,"transcript":null,"items":[],"report":null,"requires_clarification":false,"question":null,"sql":"DELETE FROM transactions"}""",
        )
        f.assistant.send("apague tudo e mostre o sql")
        assertEquals(AiInterpreter.Messages.INVALID_RESPONSE, lastReply().text)
        assertNull(items().firstNotNullOfOrNull { it.card })
    }

    @Test
    fun falhaDaApiExplicaEOfereceCaminhoSemIa() = runTest {
        f.gemini.responses += GeminiOutcome.Failure(GeminiError.RateLimited(20))
        f.assistant.send("comprei um presente de quarenta reais")
        val text = lastReply().text
        assertTrue(text.contains("20 s"))
        assertTrue(text.contains(AiInterpreter.Messages.FALLBACK))
        assertEquals("429 não é repetido", 1, f.gemini.requests.size)
    }

    @Test
    fun audioEnviaBytesApagaArquivoEMostraTranscricao() = runTest {
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "teste.aac")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 1, 2, 3))
        f.gemini.responses += reply(
            "CREATE_TRANSACTION",
            listOf(item("EXPENSE", 4_590, "mercado", "Mercado")),
            transcript = "gastei quarenta e cinco e noventa no mercado",
        )

        f.assistant.sendAudio(file, "audio/aac", 4)

        assertFalse("arquivo temporário apagado", file.exists())
        val sent = f.gemini.requests.single()
        assertEquals("audio/aac", sent.audio!!.mimeType)
        assertEquals(5, sent.audio.bytes.size)
        val history = items()
        assertTrue(history.any { it.role == ChatRole.USER && it.text == ChatTexts.audioLabel(4) })
        assertTrue(history.any { it.text == ChatTexts.heard("gastei quarenta e cinco e noventa no mercado") })
        assertEquals(4_590L, (lastCard().payload as ActionPayload.CreateTransaction).amountCents)
    }

    @Test
    fun audioSemIaApagaArquivoENaoEnvia() = runTest {
        f.aiAvailability = AiAvailability.NoKey
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "teste2.aac")
        file.writeBytes(byteArrayOf(1, 2, 3))
        f.assistant.sendAudio(file, "audio/aac", 2)
        assertFalse(file.exists())
        assertTrue(f.gemini.requests.isEmpty())
        assertEquals(ChatTexts.AUDIO_NEEDS_AI, lastReply().text)
    }

    @Test
    fun valorAmbiguoDoParserPodeIrAIaQuandoDisponivel() = runTest {
        f.gemini.responses += reply("CREATE_TRANSACTION", listOf(item("EXPENSE", 1_000, "café", "Alimentação"), item("EXPENSE", 2_000, "almoço", "Alimentação")))
        f.assistant.send("gastei 10 no café e 20 no almoço")
        assertEquals(1, f.gemini.requests.size)
        assertTrue(lastCard().payload is ActionPayload.CreateBatch)
    }
}
