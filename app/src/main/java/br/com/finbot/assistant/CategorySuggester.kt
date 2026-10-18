package br.com.finbot.assistant

import br.com.finbot.data.TransactionKind
import java.text.Normalizer

/**
 * Sugere a categoria padrão por palavras-chave na descrição. Determinístico e local.
 * Retorna o nome de uma categoria padrão; o usuário pode trocar no cartão de confirmação.
 */
object CategorySuggester {
    const val FALLBACK = "Outros"

    private val EXPENSE_RULES: List<Pair<String, List<String>>> = listOf(
        "Mercado" to listOf("mercado", "supermercado", "feira", "hortifruti", "atacadao", "acougue", "padaria"),
        "Alimentação" to listOf(
            "almoco", "jantar", "lanche", "cafe", "restaurante", "ifood", "pizza", "hamburguer", "marmita",
            "comida", "sorvete", "delivery",
        ),
        "Transporte" to listOf(
            "uber", "99", "taxi", "onibus", "metro", "trem", "gasolina", "combustivel", "etanol", "estacionamento",
            "pedagio", "passagem", "bilhete", "oficina", "mecanico",
        ),
        "Moradia" to listOf("aluguel", "condominio", "luz", "energia", "agua", "internet", "gas", "iptu", "reforma"),
        "Saúde" to listOf("farmacia", "remedio", "medico", "consulta", "dentista", "exame", "plano de saude", "hospital", "academia"),
        "Assinaturas" to listOf("netflix", "spotify", "assinatura", "streaming", "prime", "disney", "youtube", "icloud"),
        "Educação" to listOf("curso", "escola", "faculdade", "livro", "mensalidade", "material escolar"),
        "Lazer" to listOf("cinema", "show", "bar", "viagem", "passeio", "jogo", "ingresso", "festa"),
    )

    private val INCOME_RULES: List<Pair<String, List<String>>> = listOf(
        "Salário" to listOf("salario", "pagamento", "holerite", "adiantamento", "13", "decimo terceiro", "ferias"),
    )

    fun suggest(kind: TransactionKind, description: String): String {
        val words = normalize(description).split(' ').filter { it.isNotEmpty() }.toSet()
        val text = " ${normalize(description)} "
        val rules = if (kind == TransactionKind.EXPENSE) EXPENSE_RULES else INCOME_RULES
        return rules.firstOrNull { (_, keywords) ->
            keywords.any { k -> if (k.contains(' ')) text.contains(" $k ") else k in words }
        }?.first ?: FALLBACK
    }

    /** Minúsculas, sem acentos e sem pontuação, para comparar palavras. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
