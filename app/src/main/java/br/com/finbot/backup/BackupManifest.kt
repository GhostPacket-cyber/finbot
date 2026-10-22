package br.com.finbot.backup

import org.json.JSONObject

/** Contagem e soma de uma tabela. [sum] é a soma da coluna de valor (amount_cents ou limit_cents), quando existe. */
data class TableStats(val rows: Long, val sum: Long? = null)

/**
 * Manifesto do backup: confere o conteúdo depois de abrir (além da tag do GCM).
 * [balanceCents] = receitas − despesas confirmadas e não excluídas, para conferir os totais ao restaurar.
 */
data class BackupManifest(
    val format: Int = 1,
    val schemaVersion: Int,
    val createdAtEpochMs: Long,
    val appVersion: String,
    val sha256: String,
    val tables: Map<String, TableStats>,
    val balanceCents: Long,
) {
    fun toJson(): String {
        val t = JSONObject()
        tables.toSortedMap().forEach { (name, s) ->
            t.put(name, JSONObject().put("rows", s.rows).apply { s.sum?.let { put("sum", it) } })
        }
        return JSONObject()
            .put("format", format)
            .put("schemaVersion", schemaVersion)
            .put("createdAt", createdAtEpochMs)
            .put("appVersion", appVersion)
            .put("sha256", sha256)
            .put("balanceCents", balanceCents)
            .put("tables", t)
            .toString()
    }

    /** Mesmos números (contagens, somas e saldo) — o que importa para "reproduz os totais". */
    fun sameTotals(other: StatsSnapshot): Boolean = tables == other.tables && balanceCents == other.balanceCents

    companion object {
        fun fromJson(text: String): BackupManifest {
            val j = JSONObject(text)
            val t = j.getJSONObject("tables")
            val tables = t.keys().asSequence().associateWith { name ->
                val o = t.getJSONObject(name)
                TableStats(o.getLong("rows"), if (o.has("sum")) o.getLong("sum") else null)
            }
            return BackupManifest(
                format = j.getInt("format"),
                schemaVersion = j.getInt("schemaVersion"),
                createdAtEpochMs = j.getLong("createdAt"),
                appVersion = j.optString("appVersion"),
                sha256 = j.getString("sha256"),
                tables = tables,
                balanceCents = j.getLong("balanceCents"),
            )
        }
    }
}

data class StatsSnapshot(val tables: Map<String, TableStats>, val balanceCents: Long)

/** Consulta mínima, para calcular as estatísticas tanto no banco ativo (Room) quanto numa cópia (SQLiteDatabase). */
interface SqlReader {
    fun longOf(sql: String): Long
    fun strings(sql: String): List<String>
}

object BackupStats {
    /** Tabelas internas que não entram em backup, contagem ou cópia. */
    val INTERNAL = setOf("android_metadata", "room_master_table", "sqlite_sequence")

    fun userTables(db: SqlReader): List<String> =
        db.strings("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").filter { it !in INTERNAL }

    fun columns(db: SqlReader, table: String): List<String> = db.strings("SELECT name FROM pragma_table_info('$table')")

    fun compute(db: SqlReader): StatsSnapshot {
        val tables = userTables(db).associateWith { table ->
            val cols = columns(db, table)
            val valueColumn = listOf("amount_cents", "limit_cents").firstOrNull { it in cols }
            TableStats(
                rows = db.longOf("SELECT COUNT(*) FROM `$table`"),
                sum = valueColumn?.let { db.longOf("SELECT COALESCE(SUM(`$it`), 0) FROM `$table`") },
            )
        }
        val balance = if ("transactions" in tables) {
            db.longOf(
                "SELECT COALESCE(SUM(CASE WHEN kind = 'INCOME' THEN amount_cents ELSE -amount_cents END), 0) " +
                    "FROM transactions WHERE status = 'CONFIRMED' AND deleted_at IS NULL",
            )
        } else {
            0L
        }
        return StatsSnapshot(tables, balance)
    }
}
