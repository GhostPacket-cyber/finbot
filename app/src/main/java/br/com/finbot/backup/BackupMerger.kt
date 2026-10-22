package br.com.finbot.backup

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Mesclagem por `uuid` (ETAPA 11). Regra de conflito documentada em docs/DECISIONS.md (ADR-020):
 * **o que já existe neste aparelho prevalece; nada é sobrescrito.**
 * - Mesmo `uuid` → ignorado (a versão local fica).
 * - Categoria com mesmo nome e tipo, conta com mesmo nome → unificadas (os lançamentos importados apontam para a local).
 * - Lançamento com a mesma `dedup_key` (mesmo item de extrato/notificação) → ignorado.
 * - Vencimento da mesma conta na mesma data, orçamento da mesma categoria no mesmo mês, modelo CSV de mesmo nome → ignorados.
 * - Ids são remapeados; referências a algo que não veio ficam nulas (ou vão para a conta padrão local).
 * Histórico do chat, ações pendentes, registro de notificações e auditoria não são mesclados (são deste aparelho).
 */
internal class BackupMerger(private val live: SupportSQLiteDatabase, private val imp: SQLiteDatabase) {
    private val added = linkedMapOf<String, Int>()
    private val ignored = linkedMapOf<String, Int>()
    private val categoryIds = HashMap<Long, Long>()
    private val accountIds = HashMap<Long, Long>()
    private val batchIds = HashMap<Long, Long>()
    private val transactionIds = HashMap<Long, Long>()
    private val billIds = HashMap<Long, Long>()

    fun run(): MergeReport {
        merge("categories", categoryIds) { v ->
            existing("SELECT id FROM categories WHERE name = ? COLLATE NOCASE AND kind = ?", v.getAsString("name"), v.getAsString("kind"))
        }
        merge("accounts", accountIds) { v ->
            existing("SELECT id FROM accounts WHERE name = ? COLLATE NOCASE", v.getAsString("name"))
        }
        merge("import_batches", batchIds, beforeInsert = { v -> remapOptional(v, "account_id", accountIds) })
        val defaultAccount = existing("SELECT id FROM accounts WHERE archived = 0 ORDER BY id LIMIT 1")
        merge(
            "transactions",
            transactionIds,
            beforeInsert = { v ->
                remapOptional(v, "category_id", categoryIds)
                remapOptional(v, "import_batch_id", batchIds)
                val account = v.getAsLong("account_id")?.let(accountIds::get) ?: defaultAccount
                if (account == null) false else true.also { v.put("account_id", account) }
            },
        ) { v -> v.getAsString("dedup_key")?.let { existing("SELECT id FROM transactions WHERE dedup_key = ?", it) } }
        merge("bills", billIds, beforeInsert = { v -> remapOptional(v, "category_id", categoryIds) })
        merge(
            "bill_occurrences",
            null,
            beforeInsert = { v ->
                val bill = v.getAsLong("bill_id")?.let(billIds::get) ?: return@merge false
                v.put("bill_id", bill)
                remapOptional(v, "linked_transaction_id", transactionIds)
                v.getAsLong("linked_transaction_id")?.let { linked ->
                    if (existing("SELECT id FROM bill_occurrences WHERE linked_transaction_id = ?", linked.toString()) != null) v.putNull("linked_transaction_id")
                }
                true
            },
        ) { v ->
            val bill = v.getAsLong("bill_id")?.let(billIds::get) ?: return@merge null
            existing("SELECT id FROM bill_occurrences WHERE bill_id = ? AND due_date = ?", bill.toString(), v.getAsString("due_date"))
        }
        merge("reminders", null)
        merge(
            "budgets",
            null,
            beforeInsert = { v ->
                val category = v.getAsLong("category_id")?.let(categoryIds::get) ?: return@merge false
                v.put("category_id", category)
                true
            },
        ) { v ->
            val category = v.getAsLong("category_id")?.let(categoryIds::get) ?: return@merge null
            existing("SELECT id FROM budgets WHERE category_id = ? AND month = ?", category.toString(), v.getAsString("month"))
        }
        merge("csv_presets", null, byUuid = false) { v -> existing("SELECT id FROM csv_presets WHERE name = ? COLLATE NOCASE", v.getAsString("name")) }
        return MergeReport(added.toMap(), ignored.toMap())
    }

    /**
     * Mescla uma tabela. [sameAs]: outra forma de achar o equivalente local além do `uuid`.
     * [beforeInsert]: ajusta as referências; false = não importa a linha (conta como ignorada).
     */
    private fun merge(
        table: String,
        ids: MutableMap<Long, Long>?,
        byUuid: Boolean = true,
        beforeInsert: (android.content.ContentValues) -> Boolean = { true },
        sameAs: (android.content.ContentValues) -> Long? = { null },
    ) {
        if (!hasTable(table)) return
        val liveColumns = BackupStats.columns(BackupService.reader(live), table).toSet()
        var add = 0
        var skip = 0
        imp.rawQuery("SELECT * FROM `$table` ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val v = c.toValues(liveColumns)
                val importedId = v.getAsLong("id")
                val local = (if (byUuid) v.getAsString("uuid")?.let { existing("SELECT id FROM `$table` WHERE uuid = ?", it) } else null) ?: sameAs(v)
                if (local != null) {
                    if (importedId != null) ids?.put(importedId, local)
                    skip++
                    continue
                }
                v.remove("id")
                if (!beforeInsert(v)) {
                    skip++
                    continue
                }
                val newId = live.insert(table, SQLiteDatabase.CONFLICT_ABORT, v)
                if (importedId != null) ids?.put(importedId, newId)
                add++
            }
        }
        added[table] = add
        ignored[table] = skip
    }

    private fun remapOptional(v: android.content.ContentValues, column: String, map: Map<Long, Long>): Boolean {
        val old = v.getAsLong(column) ?: return true
        val mapped = map[old]
        if (mapped == null) v.putNull(column) else v.put(column, mapped)
        return true
    }

    private fun existing(sql: String, vararg args: String): Long? =
        live.query(sql, args).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun hasTable(table: String): Boolean =
        imp.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }
}
