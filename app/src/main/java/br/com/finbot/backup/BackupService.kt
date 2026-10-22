package br.com.finbot.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import br.com.finbot.data.FinBotDatabase
import br.com.finbot.importing.DedupKeys
import br.com.finbot.security.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock

/** Backup aberto, conferido e (se preciso) migrado; pronto para substituir ou mesclar. */
class OpenedBackup internal constructor(val manifest: BackupManifest, internal val file: File)

/** Resultado (ou prévia) da mesclagem por tabela. */
data class MergeReport(val added: Map<String, Int>, val ignored: Map<String, Int>) {
    val totalAdded: Int get() = added.values.sum()
    val totalIgnored: Int get() = ignored.values.sum()
}

class BackupIntegrityException(message: String) : Exception(message)

/**
 * Exportar e importar `.finbot` (ETAPA 11).
 * - Exportar: cópia consistente do banco (arquivo + WAL copiados com as escritas bloqueadas, depois compactados num
 *   arquivo só), manifesto com contagens/somas/saldo/hash e criptografia com a senha do usuário ([BackupCrypto]).
 * - Importar: confere tag, hash, integridade do SQLite, versão e totais; migra se o schema for mais antigo.
 *   **Substituir** troca todo o conteúdo numa transação; **mesclar** une por `uuid` sem sobrescrever nada local.
 * Preferências, segredos (chave da Gemini) e o histórico do chat não fazem parte do backup.
 */
class BackupService(
    private val context: Context,
    private val db: FinBotDatabase,
    private val dbFile: File,
    private val clock: Clock,
    private val appVersion: String,
) {
    private val workDir get() = File(context.cacheDir, "backup").apply { mkdirs() }

    // ---------- Exportar ----------

    suspend fun export(password: CharArray): Pair<ByteArray, BackupManifest> = withContext(Dispatchers.IO) {
        val snapshot = File(workDir, "snapshot.db")
        cleanup(snapshot)
        try {
            // Com a transação aberta nenhuma outra escrita é confirmada: banco + WAL formam um retrato consistente.
            db.withTransaction {
                dbFile.copyTo(snapshot, overwrite = true)
                val wal = File(dbFile.path + "-wal")
                if (wal.exists()) wal.copyTo(File(snapshot.path + "-wal"), overwrite = true)
            }
            // Aplica o WAL na cópia e volta ao modo de arquivo único.
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
                copy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                copy.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            }
            File(snapshot.path + "-wal").delete()
            File(snapshot.path + "-shm").delete()

            val stats = SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { BackupStats.compute(reader(it)) }
            val bytes = snapshot.readBytes()
            val manifest = BackupManifest(
                schemaVersion = FinBotDatabase.VERSION,
                createdAtEpochMs = clock.millis(),
                appVersion = appVersion,
                sha256 = DedupKeys.sha256Bytes(bytes),
                tables = stats.tables,
                balanceCents = stats.balanceCents,
            )
            val encrypted = BackupCrypto.encrypt(password, manifest.toJson(), bytes)
            bytes.fill(0)
            SafeLog.i("Backup exportado: ${stats.tables.values.sumOf { it.rows }} linhas")
            encrypted to manifest
        } finally {
            cleanup(snapshot)
        }
    }

    // ---------- Abrir e conferir ----------

    /** Lança [BackupAuthException], [BackupFormatException] ou [BackupIntegrityException]. Nada do banco atual é tocado. */
    suspend fun open(password: CharArray, file: ByteArray): OpenedBackup = withContext(Dispatchers.IO) {
        if (file.size > MAX_FILE_BYTES) throw BackupFormatException("Arquivo maior que ${MAX_FILE_BYTES / (1024 * 1024)} MB.")
        val opened = BackupCrypto.decrypt(password, file)
        val manifest = try {
            BackupManifest.fromJson(opened.manifest)
        } catch (e: org.json.JSONException) {
            throw BackupIntegrityException("Manifesto ilegível.")
        }
        if (DedupKeys.sha256Bytes(opened.db) != manifest.sha256) throw BackupIntegrityException("O conteúdo não confere com o manifesto.")
        if (manifest.schemaVersion > FinBotDatabase.VERSION) {
            throw BackupIntegrityException("Backup feito por uma versão mais nova do FinBot. Atualize o app para importar.")
        }
        val target = context.getDatabasePath(IMPORT_DB_NAME)
        cleanup(target)
        target.parentFile?.mkdirs()
        target.writeBytes(opened.db)
        opened.db.fill(0)
        try {
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                val integrity = raw.rawQuery("PRAGMA integrity_check", null).use { if (it.moveToFirst()) it.getString(0) else null }
                if (integrity != "ok") throw BackupIntegrityException("Banco do backup corrompido.")
                if (raw.version != manifest.schemaVersion) throw BackupIntegrityException("Versão do banco não confere com o manifesto.")
                if (!manifest.sameTotals(BackupStats.compute(reader(raw)))) throw BackupIntegrityException("Totais do banco não conferem com o manifesto.")
            }
            // Room confere o schema (hash de identidade) e aplica as migrações se o backup for de versão anterior.
            Room.databaseBuilder(context, FinBotDatabase::class.java, IMPORT_DB_NAME)
                .addMigrations(*FinBotDatabase.MIGRATIONS)
                .build()
                .also { it.openHelper.writableDatabase }
                .close()
            OpenedBackup(manifest, target)
        } catch (e: BackupIntegrityException) {
            cleanup(target)
            throw e
        } catch (e: Exception) {
            cleanup(target)
            SafeLog.w("Backup rejeitado na conferência", e)
            throw BackupIntegrityException("O banco do backup não é compatível com o FinBot.")
        }
    }

    fun discard(backup: OpenedBackup) = cleanup(backup.file)

    // ---------- Substituir ----------

    /** Troca todo o conteúdo do banco pelo do backup, numa transação. Confere os totais antes de confirmar. */
    suspend fun replace(backup: OpenedBackup) = withContext(Dispatchers.IO) {
        SQLiteDatabase.openDatabase(backup.file.path, null, SQLiteDatabase.OPEN_READONLY).use { imp ->
            db.withTransaction {
                val live = db.openHelper.writableDatabase
                live.execSQL("PRAGMA defer_foreign_keys = ON")
                val liveTables = BackupStats.userTables(reader(live)).toSet()
                val tables = BackupStats.userTables(reader(imp)).filter { it in liveTables }
                liveTables.forEach { live.execSQL("DELETE FROM `$it`") }
                tables.forEach { table ->
                    val liveColumns = BackupStats.columns(reader(live), table).toSet()
                    imp.rawQuery("SELECT * FROM `$table`", null).use { c ->
                        while (c.moveToNext()) live.insert(table, SQLiteDatabase.CONFLICT_ABORT, c.toValues(liveColumns))
                    }
                }
                // Se o backup era de versão anterior, ele foi migrado: as contagens continuam as mesmas.
                val after = BackupStats.compute(reader(live))
                if (after.balanceCents != backup.manifest.balanceCents ||
                    tables.any { (after.tables[it]?.rows ?: 0L) != (backup.manifest.tables[it]?.rows ?: 0L) }
                ) {
                    throw BackupIntegrityException("Os totais restaurados não conferem; nada foi alterado.")
                }
            }
        }
        SafeLog.i("Backup restaurado (substituir)")
    }

    // ---------- Mesclar ----------

    /** Simula a mesclagem e desfaz: a prévia usa exatamente as mesmas regras da gravação. */
    suspend fun previewMerge(backup: OpenedBackup): MergeReport = withContext(Dispatchers.IO) {
        var report: MergeReport? = null
        try {
            mergeInTransaction(backup) { report = it; throw PreviewRollback() }
        } catch (_: PreviewRollback) {
        }
        requireNotNull(report)
    }

    suspend fun merge(backup: OpenedBackup): MergeReport = withContext(Dispatchers.IO) {
        var report: MergeReport? = null
        mergeInTransaction(backup) { report = it }
        SafeLog.i("Backup mesclado: ${report?.totalAdded} adicionados")
        requireNotNull(report)
    }

    private class PreviewRollback : RuntimeException()

    private suspend fun mergeInTransaction(backup: OpenedBackup, onDone: (MergeReport) -> Unit) {
        SQLiteDatabase.openDatabase(backup.file.path, null, SQLiteDatabase.OPEN_READONLY).use { imp ->
            db.withTransaction {
                onDone(BackupMerger(db.openHelper.writableDatabase, imp).run())
            }
        }
    }

    private fun cleanup(file: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }

    companion object {
        const val MAX_FILE_BYTES = 50 * 1024 * 1024
        const val IMPORT_DB_NAME = "finbot-import.db"
        const val FILE_EXTENSION = "finbot"

        fun reader(db: SQLiteDatabase) = object : SqlReader {
            override fun longOf(sql: String): Long = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            override fun strings(sql: String): List<String> = db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        }

        fun reader(db: SupportSQLiteDatabase) = object : SqlReader {
            override fun longOf(sql: String): Long = db.query(sql).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            override fun strings(sql: String): List<String> = db.query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        }
    }
}

/** Valores da linha atual, só das colunas que existem no destino. */
internal fun Cursor.toValues(allowed: Set<String>? = null): ContentValues {
    val values = ContentValues()
    for (i in 0 until columnCount) {
        val name = getColumnName(i)
        if (allowed != null && name !in allowed) continue
        when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> values.putNull(name)
            Cursor.FIELD_TYPE_INTEGER -> values.put(name, getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> values.put(name, getDouble(i))
            Cursor.FIELD_TYPE_BLOB -> values.put(name, getBlob(i))
            else -> values.put(name, getString(i))
        }
    }
    return values
}
