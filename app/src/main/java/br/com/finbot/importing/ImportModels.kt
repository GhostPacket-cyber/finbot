package br.com.finbot.importing

import br.com.finbot.data.TransactionKind
import java.time.LocalDate

/** Item lido de um extrato, antes de virar sugestão (`PENDING_REVIEW`). [amountCents] é sempre positivo. */
data class StatementItem(
    val date: LocalDate,
    val kind: TransactionKind,
    val amountCents: Long,
    val description: String,
    /** Chave estável de deduplicação (ver [DedupKeys]). */
    val dedupKey: String,
    /** Referência do banco (FITID no OFX), para auditoria. */
    val externalReference: String? = null,
)

/** Linha que não pôde ser lida: o motivo é mostrado ao usuário; o resto do arquivo continua. */
data class RowError(val line: Int, val reason: String)

data class ParsedStatement(
    val format: StatementFormat,
    val items: List<StatementItem>,
    val errors: List<RowError> = emptyList(),
    /** Conta do extrato (OFX). Exibir sempre mascarada. */
    val accountId: String? = null,
    val bankId: String? = null,
)

enum class StatementFormat { OFX, CSV }

object Masking {
    /** "••••1234": nunca mostrar o número completo da conta. */
    fun account(id: String?): String? = id?.trim()?.takeIf { it.isNotEmpty() }?.let { "••••" + it.takeLast(4) }
}
