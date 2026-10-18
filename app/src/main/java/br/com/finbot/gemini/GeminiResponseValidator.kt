package br.com.finbot.gemini

import br.com.finbot.data.TransactionKind
import br.com.finbot.domain.Money
import br.com.finbot.domain.TransactionRules
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DateTimeException
import java.time.LocalDate

/** Resultado da validação: ou uma interpretação segura, ou o motivo (técnico, sem conteúdo do usuário) da rejeição. */
sealed interface ValidationOutcome {
    data class Valid(val interpretation: AiInterpretation) : ValidationOutcome
    data class Invalid(val reason: String) : ValidationOutcome
}

/**
 * Valida o JSON do modelo contra o schema fechado e as regras do app. Desconfia de tudo:
 * campos extras, tipos errados, valores negativos ou não inteiros, datas impossíveis ou futuras e categorias inexistentes.
 * Cada intent tem seu próprio validador.
 */
object GeminiResponseValidator {
    const val MIN_CONFIDENCE = 0.5
    private const val MAX_QUESTION = 300
    private const val MAX_TRANSCRIPT = 500

    fun validate(raw: String, today: LocalDate, categories: AllowedCategories): ValidationOutcome {
        val json = try {
            JSONObject(stripCodeFence(raw))
        } catch (_: JSONException) {
            return invalid("JSON malformado")
        }
        val extra = json.keyNames() - GeminiPrompt.TOP_LEVEL_KEYS
        if (extra.isNotEmpty()) return invalid("campos extras: ${extra.size}")
        val missing = GeminiPrompt.TOP_LEVEL_KEYS - json.keyNames()
        if (missing.isNotEmpty()) return invalid("campos ausentes: ${missing.sorted()}")

        val intent = enumValue<AiIntent>(json, "intent") ?: return invalid("intent inválida")
        val confidence = json.opt("confidence")
        if (confidence !is Number || confidence.toDouble() !in 0.0..1.0) return invalid("confidence inválida")
        val clarify = json.opt("requires_clarification") as? Boolean ?: return invalid("requires_clarification inválido")
        val transcript = optionalText(json, "transcript", MAX_TRANSCRIPT) ?: return invalid("transcript inválido")
        val question = optionalText(json, "question", MAX_QUESTION) ?: return invalid("question inválida")
        val items = json.opt("items") as? JSONArray ?: return invalid("items inválido")
        val transcriptValue = transcript.value

        if (clarify) {
            val text = question.value ?: DEFAULT_QUESTION
            return ValidationOutcome.Valid(AiInterpretation.Clarification(text, transcriptValue))
        }

        return when (intent) {
            AiIntent.CREATE_TRANSACTION -> validateTransactions(items, confidence.toDouble(), today, categories, transcriptValue)
            AiIntent.GET_REPORT, AiIntent.QUERY_TRANSACTIONS -> validateReport(json, items, transcriptValue)
            AiIntent.CREATE_BILL, AiIntent.MARK_BILL_PAID, AiIntent.CREATE_REMINDER ->
                if (items.length() == 0) ValidationOutcome.Valid(AiInterpretation.NotSupportedYet(intent, transcriptValue))
                else invalid("itens em intent ${intent.name}")
            AiIntent.UNKNOWN -> ValidationOutcome.Valid(AiInterpretation.Unknown(transcriptValue))
        }
    }

    const val DEFAULT_QUESTION = "Não entendi direito. Pode repetir com o valor e o que foi, por exemplo: gastei 45 no mercado?"
    const val LOW_CONFIDENCE_QUESTION = "Não tenho certeza do que entendi. Pode reformular com o valor e o que foi?"

    private fun validateTransactions(
        items: JSONArray,
        confidence: Double,
        today: LocalDate,
        categories: AllowedCategories,
        transcript: String?,
    ): ValidationOutcome {
        if (items.length() == 0) return invalid("CREATE_TRANSACTION sem itens")
        if (items.length() > GeminiPrompt.MAX_ITEMS) return invalid("itens demais")
        if (confidence < MIN_CONFIDENCE) {
            return ValidationOutcome.Valid(AiInterpretation.Clarification(LOW_CONFIDENCE_QUESTION, transcript))
        }
        val parsed = mutableListOf<AiItem>()
        for (i in 0 until items.length()) {
            val obj = items.opt(i) as? JSONObject ?: return invalid("item $i não é objeto")
            when (val r = validateItem(obj, today, categories)) {
                is ItemResult.Ok -> parsed += r.item
                is ItemResult.Error -> return invalid("item $i: ${r.reason}")
            }
        }
        return ValidationOutcome.Valid(AiInterpretation.Transactions(parsed, transcript))
    }

    private sealed interface ItemResult {
        data class Ok(val item: AiItem) : ItemResult
        data class Error(val reason: String) : ItemResult
    }

    private fun validateItem(obj: JSONObject, today: LocalDate, categories: AllowedCategories): ItemResult {
        val extra = obj.keyNames() - GeminiPrompt.ITEM_KEYS
        if (extra.isNotEmpty()) return ItemResult.Error("campos extras")
        if ((GeminiPrompt.ITEM_KEYS - obj.keyNames()).isNotEmpty()) return ItemResult.Error("campos ausentes")

        val kind = enumValue<TransactionKind>(obj, "kind") ?: return ItemResult.Error("kind inválido")

        // Só inteiros exatos: 45.9 ou "4590" (texto) são rejeitados.
        val amount = obj.opt("amount_cents")
        val cents = (amount as? Int)?.toLong() ?: (amount as? Long)
            ?: return ItemResult.Error("amount_cents não inteiro")
        if (!Money.isValidAmount(cents)) return ItemResult.Error("amount_cents fora do intervalo")
        if (obj.opt("currency") != "BRL") return ItemResult.Error("moeda não suportada")

        val description = (obj.opt("description") as? String)?.let(::cleanText)
            ?: return ItemResult.Error("description inválida")
        if (description.isEmpty() || description.length > TransactionRules.MAX_DESCRIPTION) {
            return ItemResult.Error("description vazia ou longa")
        }

        val rawCategory = obj.opt("category")
        val category = if (rawCategory == null || rawCategory == JSONObject.NULL) {
            null
        } else {
            val name = rawCategory as? String ?: return ItemResult.Error("category inválida")
            canonicalCategory(name, kind, categories) ?: return ItemResult.Error("categoria inexistente")
        }

        val date = try {
            LocalDate.parse(obj.opt("date") as? String ?: return ItemResult.Error("date inválida"))
        } catch (_: DateTimeException) {
            return ItemResult.Error("date impossível")
        }
        if (date.isAfter(today)) return ItemResult.Error("date no futuro")
        if (date.isBefore(LocalDate.of(2000, 1, 1))) return ItemResult.Error("date antiga demais")

        return ItemResult.Ok(AiItem(kind, cents, description, category, date))
    }

    private fun validateReport(json: JSONObject, items: JSONArray, transcript: String?): ValidationOutcome {
        if (items.length() != 0) return invalid("relatório com itens")
        val report = enumValue<AiReport>(json, "report") ?: return invalid("report inválido")
        return ValidationOutcome.Valid(AiInterpretation.Report(report, transcript))
    }

    private fun canonicalCategory(name: String, kind: TransactionKind, categories: AllowedCategories): String? {
        val allowed = if (kind == TransactionKind.EXPENSE) categories.expense else categories.income
        return allowed.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
    }

    private inline fun <reified E : Enum<E>> enumValue(json: JSONObject, key: String): E? {
        val value = json.opt(key) as? String ?: return null
        return enumValues<E>().firstOrNull { it.name == value }
    }

    /** Texto opcional: null do JSON vira Holder(null); tipo errado vira null (inválido). */
    private data class Holder(val value: String?)

    private fun optionalText(json: JSONObject, key: String, max: Int): Holder? {
        val v = json.opt(key)
        if (v == null || v == JSONObject.NULL) return Holder(null)
        val text = v as? String ?: return null
        return Holder(cleanText(text).take(max).ifEmpty { null })
    }

    /** Remove caracteres de controle (exceto quebra de linha) e espaços repetidos. */
    fun cleanText(text: String): String =
        text.filter { !it.isISOControl() || it == '\n' }
            .replace(Regex("[ \\t]+"), " ")
            .trim()

    /** Alguns modelos embrulham o JSON em ```json ... ```; o conteúdo continua sendo validado igual. */
    fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
    }

    private fun invalid(reason: String) = ValidationOutcome.Invalid(reason)
}

/** Nomes das chaves; `keySet()` não é API pública no org.json do Android. */
internal fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()
