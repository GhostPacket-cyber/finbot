package br.com.finbot.gemini

import br.com.finbot.data.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GeminiResponseValidatorTest {

    private val today = LocalDate.of(2026, 10, 18)
    private val categories = AllowedCategories(
        expense = listOf("Alimentação", "Mercado", "Transporte", "Outros"),
        income = listOf("Salário", "Outros"),
    )

    private fun item(
        kind: String = "EXPENSE",
        amount: String = "4590",
        description: String = "\"mercado\"",
        category: String = "\"Mercado\"",
        date: String = "\"2026-10-18\"",
        extra: String = "",
    ) = """{"kind":"$kind","amount_cents":$amount,"currency":"BRL","description":$description,"category":$category,"date":$date$extra}"""

    private fun response(
        intent: String = "CREATE_TRANSACTION",
        items: String = "[${item()}]",
        confidence: String = "0.95",
        report: String = "null",
        clarify: Boolean = false,
        question: String = "null",
        transcript: String = "null",
        extra: String = "",
    ) = """{"intent":"$intent","confidence":$confidence,"transcript":$transcript,"items":$items,"report":$report,"requires_clarification":$clarify,"question":$question$extra}"""

    private fun valid(json: String): AiInterpretation {
        val outcome = GeminiResponseValidator.validate(json, today, categories)
        assertTrue("esperava válido, veio $outcome", outcome is ValidationOutcome.Valid)
        return (outcome as ValidationOutcome.Valid).interpretation
    }

    private fun invalid(json: String) {
        val outcome = GeminiResponseValidator.validate(json, today, categories)
        assertTrue("esperava inválido para $json", outcome is ValidationOutcome.Invalid)
    }

    @Test
    fun `lancamento unico valido`() {
        val r = valid(response()) as AiInterpretation.Transactions
        assertEquals(listOf(AiItem(TransactionKind.EXPENSE, 4590, "mercado", "Mercado", today)), r.items)
    }

    @Test
    fun `varios itens e categoria nula ou com caixa diferente`() {
        val items = "[${item()},${item(amount = "1200", description = "\"uber\"", category = "\"transporte\"")},${item(category = "null")}]"
        val r = valid(response(items = items)) as AiInterpretation.Transactions
        assertEquals(3, r.items.size)
        assertEquals("Transporte", r.items[1].category)
        assertEquals(null, r.items[2].category)
    }

    @Test
    fun `rejeita campos extras no topo e no item`() {
        invalid(response(extra = ""","sql":"DROP TABLE transactions""""))
        invalid(response(items = "[${item(extra = ""","user_id":2""")}]"))
    }

    @Test
    fun `rejeita campos ausentes e tipos errados`() {
        invalid("""{"intent":"CREATE_TRANSACTION","items":[]}""")
        invalid(response(confidence = "\"alta\""))
        invalid(response(confidence = "1.5"))
        invalid(response(intent = "DELETE_ALL"))
        invalid(response(items = "{}"))
    }

    @Test
    fun `rejeita valores negativos zero fracionarios texto e acima do limite`() {
        listOf("-4590", "0", "45.9", "\"4590\"", "1000000001", "99999999999999999999").forEach {
            invalid(response(items = "[${item(amount = it)}]"))
        }
    }

    @Test
    fun `rejeita datas impossiveis futuras ou antigas`() {
        listOf("\"2026-02-30\"", "\"2026-10-19\"", "\"1999-12-31\"", "\"18/10/2026\"", "null").forEach {
            invalid(response(items = "[${item(date = it)}]"))
        }
    }

    @Test
    fun `rejeita categoria inexistente ou de outro tipo`() {
        invalid(response(items = "[${item(category = "\"Cassino\"")}]"))
        invalid(response(items = "[${item(kind = "INCOME", category = "\"Mercado\"")}]"))
    }

    @Test
    fun `rejeita moeda descricao vazia e itens demais`() {
        invalid(response(items = "[${item().replace("\"BRL\"", "\"USD\"")}]"))
        invalid(response(items = "[${item(description = "\"   \"")}]"))
        invalid(response(items = "[${(1..11).joinToString(",") { item() }}]"))
        invalid(response(items = "[]"))
    }

    @Test
    fun `esclarecimento e baixa confianca viram pergunta`() {
        val c = valid(response(items = "[]", clarify = true, question = "\"Qual foi o valor?\"")) as AiInterpretation.Clarification
        assertEquals("Qual foi o valor?", c.question)
        val low = valid(response(confidence = "0.3")) as AiInterpretation.Clarification
        assertEquals(GeminiResponseValidator.LOW_CONFIDENCE_QUESTION, low.question)
        val noQuestion = valid(response(items = "[]", clarify = true)) as AiInterpretation.Clarification
        assertEquals(GeminiResponseValidator.DEFAULT_QUESTION, noQuestion.question)
    }

    @Test
    fun `relatorios contas lembretes e desconhecido`() {
        assertEquals(AiReport.MONTH, (valid(response(intent = "GET_REPORT", items = "[]", report = "\"MONTH\"")) as AiInterpretation.Report).report)
        invalid(response(intent = "GET_REPORT", items = "[]", report = "\"DELETE\""))
        invalid(response(intent = "GET_REPORT", report = "\"MONTH\"")) // relatório com itens
        assertTrue(valid(response(intent = "CREATE_BILL", items = "[]")) is AiInterpretation.NotSupportedYet)
        assertTrue(valid(response(intent = "UNKNOWN", items = "[]")) is AiInterpretation.Unknown)
    }

    @Test
    fun `transcricao e pergunta sao limpas e limitadas`() {
        val r = valid(response(transcript = "\"gastei\\u0007 45   no mercado\"")) as AiInterpretation.Transactions
        assertEquals("gastei 45 no mercado", r.transcript)
        val long = valid(response(items = "[]", clarify = true, question = "\"${"a".repeat(1000)}\"")) as AiInterpretation.Clarification
        assertEquals(300, long.question.length)
    }

    @Test
    fun `json malformado ou cercado por bloco de codigo`() {
        invalid("não é json")
        invalid("""{"intent":"CREATE_TRANSACTION",""")
        assertTrue(valid("```json\n${response()}\n```") is AiInterpretation.Transactions)
    }
}
