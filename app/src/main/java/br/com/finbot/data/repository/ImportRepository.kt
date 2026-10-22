package br.com.finbot.data.repository

import androidx.room.withTransaction
import br.com.finbot.assistant.CategorySuggester
import br.com.finbot.data.AuditAction
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.data.ImportSource
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.dao.ImportBatchRow
import br.com.finbot.data.entity.CsvPresetEntity
import br.com.finbot.data.entity.ImportBatchEntity
import br.com.finbot.data.entity.TransactionEntity
import br.com.finbot.domain.TransactionDraft
import br.com.finbot.domain.TransactionRules
import br.com.finbot.importing.CsvDateFormat
import br.com.finbot.importing.CsvMapping
import br.com.finbot.importing.DedupKeys
import br.com.finbot.importing.Masking
import br.com.finbot.importing.ParsedStatement
import br.com.finbot.importing.StatementFormat
import br.com.finbot.importing.StatementItem
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** De onde veio a categoria sugerida para um item importado. */
enum class CategoryOrigin { HISTORY, KEYWORD, AI }

/** Um item do extrato já comparado com o banco de dados, pronto para a pré-visualização. */
data class ImportCandidate(
    val index: Int,
    val item: StatementItem,
    val categoryId: Long?,
    val categoryOrigin: CategoryOrigin?,
    /** Já importado antes (mesma chave): será ignorado. */
    val alreadyImported: Boolean,
    /** Lançamento manual parecido (mesmo tipo e valor, ±2 dias): o usuário decide. */
    val possibleDuplicateOf: TransactionEntity?,
    /** Motivo de não poder importar (ex.: data no futuro). */
    val invalidReason: String?,
) {
    val importable: Boolean get() = !alreadyImported && invalidReason == null

    /** Seleção inicial: novos e sem suspeita de duplicata. */
    val selectedByDefault: Boolean get() = importable && possibleDuplicateOf == null
}

data class ImportPreview(
    val statement: ParsedStatement,
    val candidates: List<ImportCandidate>,
) {
    val maskedAccount: String? get() = Masking.account(statement.accountId)
    val newCount: Int get() = candidates.count { it.importable }
    val alreadyImportedCount: Int get() = candidates.count { it.alreadyImported }
    val possibleDuplicateCount: Int get() = candidates.count { it.importable && it.possibleDuplicateOf != null }
}

data class ImportResult(val batchId: Long?, val created: Int, val skipped: Int)

/**
 * Importação de extratos (ETAPA 08): pré-visualização com deduplicação e categorias sugeridas,
 * gravação atômica em lote (`import_batches`) como PENDING_REVIEW e desfazer o lote inteiro.
 */
class ImportRepository(
    private val db: FinBotDatabase,
    private val clock: Clock,
    private val transactions: TransactionRepository,
    private val accounts: AccountRepository,
) {
    private val dao = db.transactionDao()
    private val batches = db.importBatchDao()
    private val presets = db.csvPresetDao()
    private val categories = db.categoryDao()
    private val audit = AuditLogger(db.auditLogDao())

    suspend fun preview(statement: ParsedStatement, zone: ZoneId): ImportPreview {
        val now = Instant.now(clock)
        val existing = statement.items.map { it.dedupKey }.distinct().chunked(900).flatMap { dao.existingDedupKeys(it) }.toSet()
        val history = categoryHistory()
        val candidates = statement.items.mapIndexed { index, item ->
            val occurredAt = occurredAt(item.date, zone, now)
            val invalid = when {
                occurredAt > now.plus(TransactionRules.MAX_FUTURE) -> "data no futuro"
                else -> null
            }
            val (categoryId, origin) = suggestCategory(item, history)
            ImportCandidate(
                index = index,
                item = item,
                categoryId = categoryId,
                categoryOrigin = origin,
                alreadyImported = item.dedupKey in existing,
                possibleDuplicateOf = if (item.dedupKey in existing) null else manualMatch(item, zone),
                invalidReason = invalid,
            )
        }
        return ImportPreview(statement, candidates)
    }

    /**
     * Grava os itens escolhidos como PENDING_REVIEW num único lote: tudo ou nada.
     * Itens cuja chave apareceu entre a pré-visualização e a gravação são ignorados (não duplicam).
     * [categoryOverrides]: índice do candidato → categoria escolhida (null = sem categoria).
     */
    private suspend fun commitAtomic(
        preview: ImportPreview,
        selected: Set<Int>,
        zone: ZoneId,
        fileName: String,
        accountId: Long? = null,
        categoryOverrides: Map<Int, Long?> = emptyMap(),
    ): ImportResult {
        val chosen = preview.candidates.filter { it.index in selected && it.importable }
        if (chosen.isEmpty()) return ImportResult(null, 0, 0)
        val now = Instant.now(clock)
        val source = if (preview.statement.format == StatementFormat.OFX) TransactionSource.IMPORT_OFX else TransactionSource.IMPORT_CSV
        return db.withTransaction {
            val targetAccount = accountId ?: accounts.ensureDefault().id
            val batch = ImportBatchEntity(
                uuid = newUuid(),
                source = if (source == TransactionSource.IMPORT_OFX) ImportSource.OFX else ImportSource.CSV,
                fileNameHash = DedupKeys.sha256(fileName.trim().lowercase()),
                importedAt = now,
                itemCount = 0,
                accountId = targetAccount,
                maskedAccount = preview.maskedAccount,
            )
            val batchId = batches.insert(batch)
            var created = 0
            chosen.forEach { c ->
                val categoryId = if (c.index in categoryOverrides) categoryOverrides[c.index] else c.categoryId
                val draft = TransactionDraft(
                    kind = c.item.kind,
                    amountCents = c.item.amountCents,
                    description = c.item.description,
                    categoryId = categoryId,
                    accountId = targetAccount,
                    occurredAt = occurredAt(c.item.date, zone, now),
                )
                val id = transactions.createIfNew(
                    draft, source, c.item.dedupKey,
                    externalReference = c.item.externalReference,
                    importBatchId = batchId,
                )
                if (id != null) created++
            }
            val skipped = chosen.size - created + preview.alreadyImportedCount
            if (created == 0) {
                // Tudo já existia: o lote vazio não é mantido (a transação inteira é revertida).
                throw NothingImported(skipped)
            }
            batches.update(batch.copy(id = batchId, itemCount = created, skippedCount = skipped))
            ImportResult(batchId, created, skipped)
        }
    }

    /** Grava o lote; se nada for novo, não cria lote e devolve só a contagem de ignorados. */
    suspend fun commit(
        preview: ImportPreview,
        selected: Set<Int>,
        zone: ZoneId,
        fileName: String,
        accountId: Long? = null,
        categoryOverrides: Map<Int, Long?> = emptyMap(),
    ): ImportResult = try {
        commitAtomic(preview, selected, zone, fileName, accountId, categoryOverrides)
    } catch (e: NothingImported) {
        ImportResult(null, 0, e.skipped)
    }

    /**
     * Desfaz o lote inteiro: exclusão lógica dos lançamentos ainda ativos (confirmados ou em revisão),
     * com auditoria, e libera as chaves de deduplicação para permitir reimportar o arquivo.
     * Não toca em lançamentos de outros lotes nem em lançamentos manuais.
     */
    suspend fun undoBatch(batchId: Long): Int {
        val now = Instant.now(clock)
        return db.withTransaction {
            val batch = requireNotNull(batches.getById(batchId)) { "Lote inexistente" }
            if (batch.undoneAt != null) return@withTransaction 0
            val items = dao.listByBatch(batchId)
            var removed = 0
            items.filter { it.deletedAt == null }.forEach {
                transactions.softDelete(it.id)
                removed++
            }
            dao.releaseBatchDedupKeys(batchId, now)
            batches.update(batch.copy(undoneAt = now))
            audit.record(BATCH, batch.uuid, AuditAction.DELETE, now, mapOf("removed" to removed, "reason" to "undo_import"))
            removed
        }
    }

    fun observeBatches(): Flow<List<ImportBatchRow>> = batches.observeAll()

    // ---------- Presets de CSV ----------

    fun observePresets(): Flow<List<CsvPresetEntity>> = presets.observeAll()

    suspend fun listPresets(): List<CsvPresetEntity> = presets.listAll()

    /** Salva (ou substitui, pelo nome) o mapeamento de um banco. */
    suspend fun savePreset(name: String, mapping: CsvMapping): Long {
        val clean = name.trim().take(40)
        require(clean.isNotEmpty()) { "Informe um nome para o modelo" }
        require(mapping.isValid) { "Mapeamento incompleto" }
        val now = Instant.now(clock)
        return db.withTransaction {
            val current = presets.findByName(clean)
            val entity = CsvPresetEntity(
                id = current?.id ?: 0,
                name = current?.name ?: clean,
                separator = mapping.separator.toString(),
                skipLines = mapping.skipLines,
                skipFooterLines = mapping.skipFooterLines,
                dateColumn = mapping.dateColumn,
                descriptionColumn = mapping.descriptionColumn,
                amountColumn = mapping.amountColumn,
                debitColumn = mapping.debitColumn,
                creditColumn = mapping.creditColumn,
                dateFormat = mapping.dateFormat.name,
                invertSign = mapping.invertSign,
                updatedAt = now,
            )
            if (current == null) presets.insert(entity) else entity.id.also { presets.update(entity) }
        }
    }

    suspend fun deletePreset(id: Long) = presets.delete(id)

    // ---------- Internos ----------

    /** Data do extrato → meio-dia local (evita trocar de dia com fuso); hoje nunca vai além de "agora". */
    private fun occurredAt(date: LocalDate, zone: ZoneId, now: Instant): Instant {
        val noon = date.atTime(LocalTime.NOON).atZone(zone).toInstant()
        return if (date == now.atZone(zone).toLocalDate() && noon > now) now else noon
    }

    private suspend fun manualMatch(item: StatementItem, zone: ZoneId): TransactionEntity? {
        val start = item.date.minusDays(DUPLICATE_WINDOW_DAYS).atStartOfDay(zone).toInstant()
        val end = item.date.plusDays(DUPLICATE_WINDOW_DAYS + 1).atStartOfDay(zone).toInstant()
        return dao.findManualMatch(item.kind, item.amountCents, start, end)
    }

    private class History(val byDescription: Map<String, Long>, val activeIds: Set<Long>, val byName: Map<Pair<TransactionKind, String>, Long>)

    private suspend fun categoryHistory(): History {
        val active = TransactionKind.entries.flatMap { categories.listActive(it) }
        val activeIds = active.map { it.id }.toSet()
        val map = LinkedHashMap<String, Long>()
        dao.listCategorizedHistory(HISTORY_LIMIT).forEach { t ->
            val id = t.categoryId ?: return@forEach
            if (id in activeIds) map.putIfAbsent(historyKey(t.kind, t.description), id)
        }
        return History(map, activeIds, active.associate { (it.kind to it.name) to it.id })
    }

    private fun historyKey(kind: TransactionKind, description: String) = "$kind|${CategorySuggester.normalize(description)}"

    /** Categoria para uma descrição avulsa (ex.: notificação de banco): histórico do usuário, depois palavra-chave. */
    suspend fun suggestCategory(kind: TransactionKind, description: String): Long? =
        suggestCategory(kind, description, categoryHistory()).first

    private fun suggestCategory(item: StatementItem, history: History) = suggestCategory(item.kind, item.description, history)

    private fun suggestCategory(kind: TransactionKind, description: String, history: History): Pair<Long?, CategoryOrigin?> {
        history.byDescription[historyKey(kind, description)]?.let { return it to CategoryOrigin.HISTORY }
        val name = CategorySuggester.suggest(kind, description)
        if (name == CategorySuggester.FALLBACK) return null to null
        return history.byName[kind to name]?.let { it to CategoryOrigin.KEYWORD } ?: (null to null)
    }

    private class NothingImported(val skipped: Int) : RuntimeException()

    companion object {
        const val BATCH = "import_batch"
        const val DUPLICATE_WINDOW_DAYS = 2L
        private const val HISTORY_LIMIT = 2000

        fun presetToMapping(p: CsvPresetEntity): CsvMapping = CsvMapping(
            separator = p.separator.firstOrNull() ?: ';',
            skipLines = p.skipLines,
            dateColumn = p.dateColumn,
            descriptionColumn = p.descriptionColumn,
            amountColumn = p.amountColumn,
            debitColumn = p.debitColumn,
            creditColumn = p.creditColumn,
            dateFormat = runCatching { CsvDateFormat.valueOf(p.dateFormat) }.getOrDefault(CsvDateFormat.DMY),
            invertSign = p.invertSign,
            skipFooterLines = p.skipFooterLines,
        )
    }
}
